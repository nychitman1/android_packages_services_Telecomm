/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.annotations.VisibleForTesting;
import org.codeaurora.internal.IExtTelephony;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Utilities to deal with the system telephony services. The system telephony services are treated
 * differently from 3rd party services in some situations (emergency calls, audio focus, etc...).
 */
public final class TelephonyUtil {
    private static final String TELEPHONY_PACKAGE_NAME = "com.android.phone";

    private static final String PSTN_CALL_SERVICE_CLASS_NAME =
            "com.android.services.telephony.TelephonyConnectionService";

    private static final String LOG_TAG = "TelephonyUtil";

    private static final PhoneAccountHandle DEFAULT_EMERGENCY_PHONE_ACCOUNT_HANDLE =
            new PhoneAccountHandle(
                    new ComponentName(TELEPHONY_PACKAGE_NAME, PSTN_CALL_SERVICE_CLASS_NAME), "E");

    private TelephonyUtil() {}

    /**
     * @return fallback {@link PhoneAccount} to be used by Telecom for emergency calls in the
     * rare case that Telephony has not registered any phone accounts yet. Details about this
     * account are not expected to be displayed in the UI, so the description, etc are not
     * populated.
     */
    @VisibleForTesting
    public static PhoneAccount getDefaultEmergencyPhoneAccount() {
        return PhoneAccount.builder(DEFAULT_EMERGENCY_PHONE_ACCOUNT_HANDLE, "E")
                .setCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION |
                        PhoneAccount.CAPABILITY_CALL_PROVIDER |
                        PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS)
                .setIsEnabled(true)
                .build();
    }

    static boolean isPstnComponentName(ComponentName componentName) {
        final ComponentName pstnComponentName = new ComponentName(
                TELEPHONY_PACKAGE_NAME, PSTN_CALL_SERVICE_CLASS_NAME);
        return pstnComponentName.equals(componentName);
    }

    public static boolean shouldProcessAsEmergency(Context context, Uri handle) {
        return handle != null && isLocalEmergencyNumber(handle.getSchemeSpecificPart());
    }

    public static boolean isLocalEmergencyNumber(String address) {
        IExtTelephony mIExtTelephony =
            IExtTelephony.Stub.asInterface(ServiceManager.getService("extphone"));
        boolean result = false;
        try {
            result = mIExtTelephony.isLocalEmergencyNumber(address);
        }catch (RemoteException ex) {
            Log.e(LOG_TAG, ex, "RemoteException");
        } catch (NullPointerException ex) {
            Log.e(LOG_TAG, ex, "NullPointerException");
        }
        return result;
    }

    public static boolean isPotentialLocalEmergencyNumber(String address) {
        IExtTelephony mIExtTelephony =
            IExtTelephony.Stub.asInterface(ServiceManager.getService("extphone"));
        boolean result = false;
        try {
            result = mIExtTelephony.isPotentialLocalEmergencyNumber(address);
        }catch (RemoteException ex) {
            Log.e(LOG_TAG, ex, "RemoteException");
        } catch (NullPointerException ex) {
            Log.e(LOG_TAG, ex, "NullPointerException");
        }
        return result;
    }

    public static void sortSimPhoneAccounts(Context context, List<PhoneAccount> accounts) {
        final TelephonyManager telephonyManager = TelephonyManager.from(context);

        // Sort the accounts according to how we want to display them.
        Collections.sort(accounts, new Comparator<PhoneAccount>() {
            @Override
            public int compare(PhoneAccount account1, PhoneAccount account2) {
                int retval = 0;

                // SIM accounts go first
                boolean isSim1 = account1.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
                boolean isSim2 = account2.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
                if (isSim1 != isSim2) {
                    retval = isSim1 ? -1 : 1;
                }

                int subId1 = telephonyManager.getSubIdForPhoneAccount(account1);
                int subId2 = telephonyManager.getSubIdForPhoneAccount(account2);
                if (subId1 != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                        subId2 != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    retval = (SubscriptionManager.getSlotId(subId1) <
                            SubscriptionManager.getSlotId(subId2)) ? -1 : 1;
                }

                // Then order by package
                if (retval == 0) {
                    String pkg1 = account1.getAccountHandle().getComponentName().getPackageName();
                    String pkg2 = account2.getAccountHandle().getComponentName().getPackageName();
                    retval = pkg1.compareTo(pkg2);
                }

                // Finally, order by label
                if (retval == 0) {
                    String label1 = nullToEmpty(account1.getLabel().toString());
                    String label2 = nullToEmpty(account2.getLabel().toString());
                    retval = label1.compareTo(label2);
                }

                // Then by hashcode
                if (retval == 0) {
                    retval = account1.hashCode() - account2.hashCode();
                }
                return retval;
            }
        });
    }

    private static String nullToEmpty(String str) {
        return str == null ? "" : str;
    }

    static ComponentName getDialerComponentName(Context context) {
        Resources resources = context.getResources();
        PackageManager packageManager = context.getPackageManager();
        Intent i = new Intent(Intent.ACTION_DIAL);
        List<ResolveInfo> resolveInfo = packageManager.queryIntentActivities(i, 0);
        List<String> entries = Arrays.asList(resources.getStringArray(
                R.array.dialer_default_classes));
        for (ResolveInfo info : resolveInfo) {
            ComponentName componentName = new ComponentName(info.activityInfo.packageName,
                    info.activityInfo.name);
            if (entries.contains(componentName.flattenToString())) {
                return componentName;
            }
        }
        return null;
    }

    static ComponentName getInCallComponentName(Context context) {
        Resources resources = context.getResources();
        PackageManager packageManager = context.getPackageManager();
        Intent i = new Intent(InCallService.SERVICE_INTERFACE);
        List<ResolveInfo> resolveInfo = packageManager.queryIntentServices(i, 0);
        List<String> entries = Arrays.asList(resources.getStringArray(
                R.array.incall_default_classes));
        for (ResolveInfo info : resolveInfo) {
            ComponentName componentName = new ComponentName(info.serviceInfo.packageName,
                    info.serviceInfo.name);
            if (entries.contains(componentName.flattenToString())) {
                return componentName;
            }
        }
        return null;
    }
}
