/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.voip;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ForegroundServiceDelegationOptions;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telecom.Log;
import android.telecom.PhoneAccountHandle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManagerListenerBase;
import com.android.server.telecom.TelecomSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VoipCallMonitor extends CallsManagerListenerBase {

    private final List<Call> mPendingCalls;
    private final Map<StatusBarNotification, Call> mNotifications;
    private final Map<PhoneAccountHandle, Set<Call>> mPhoneAccountHandleListMap;
    private ActivityManagerInternal mActivityManagerInternal;
    private final Map<PhoneAccountHandle, ServiceConnection> mServices;
    private NotificationListenerService mNotificationListener;
    private final Object mLock = new Object();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final Context mContext;

    public VoipCallMonitor(Context context, TelecomSystem.SyncRoot lock) {
        mContext = context;
        mHandlerThread = new HandlerThread(this.getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mPendingCalls = new ArrayList<>();
        mNotifications = new HashMap<>();
        mServices = new HashMap<>();
        mPhoneAccountHandleListMap = new HashMap<>();
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);

        mNotificationListener = new NotificationListenerService() {
            @Override
            public void onNotificationPosted(StatusBarNotification sbn) {
                synchronized (mLock) {
                    if (mPendingCalls.isEmpty()) {
                        return;
                    }
                    if (sbn.getNotification().isStyle(Notification.CallStyle.class)) {
                        String packageName = sbn.getPackageName();
                        UserHandle userHandle = sbn.getUser();

                        for (Call call : mPendingCalls) {
                            if (packageName != null &&
                                    packageName.equals(call.getTargetPhoneAccount()
                                            .getComponentName().getPackageName())
                                    && userHandle != null
                                    && userHandle.equals(call.getInitiatingUser())) {
                                mPendingCalls.remove(call);
                                mNotifications.put(sbn, call);
                                break;
                            }
                        }
                    }
                }
            }

            @Override
            public void onNotificationRemoved(StatusBarNotification sbn) {
                synchronized (mLock) {
                    if (mNotifications.isEmpty()) {
                        return;
                    }
                    Call call = mNotifications.getOrDefault(sbn, null);
                    if (call != null) {
                        mNotifications.remove(sbn, call);
                        stopFGSDelegation(call.getTargetPhoneAccount());
                    }
                }
            }
        };

    }

    public void startMonitor() {
        try {
            mNotificationListener.registerAsSystemService(mContext,
                    new ComponentName(this.getClass().getPackageName(),
                            this.getClass().getCanonicalName()), ActivityManager.getCurrentUser());
        } catch (RemoteException e) {
            Log.e(this, e, "Cannot register notification listener");
        }
    }

    public void stopMonitor() {
        try {
            mNotificationListener.unregisterAsSystemService();
        } catch (RemoteException e) {
            Log.e(this, e, "Cannot unregister notification listener");
        }
    }

    @Override
    public void onCallAdded(Call call) {
        if (!call.isTransactionalCall()) {
            return;
        }

        synchronized (mLock) {
            PhoneAccountHandle phoneAccountHandle = call.getTargetPhoneAccount();
            Set<Call> callList = mPhoneAccountHandleListMap.computeIfAbsent(phoneAccountHandle,
                    k -> new HashSet<>());
            callList.add(call);

            mHandler.post(() -> startFGSDelegation(call.getOwnerPid(),
                    phoneAccountHandle.getUserHandle().getIdentifier(), call));
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        if (!call.isTransactionalCall()) {
            return;
        }

        synchronized (mLock) {
            stopMonitorWorks(call);
            PhoneAccountHandle phoneAccountHandle = call.getTargetPhoneAccount();
            Set<Call> callList = mPhoneAccountHandleListMap.computeIfAbsent(phoneAccountHandle,
                    k -> new HashSet<>());
            callList.remove(call);

            if (callList.isEmpty()) {
                stopFGSDelegation(phoneAccountHandle);
            }
        }
    }

    private void startFGSDelegation(int pid, int uid, Call call) {
        Log.i(this, "startFGSDelegation for call %s", call.getId());
        if (mActivityManagerInternal != null) {
            PhoneAccountHandle handle = call.getTargetPhoneAccount();
            ForegroundServiceDelegationOptions options = new ForegroundServiceDelegationOptions(pid,
                    uid, handle.getComponentName().getPackageName(), null /* clientAppThread */,
                    false /* isSticky */, String.valueOf(handle.hashCode()),
                    0 /* foregroundServiceType */,
                    ForegroundServiceDelegationOptions.DELEGATION_SERVICE_PHONE_CALL);
            ServiceConnection fgsConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    mServices.put(handle, this);
                    startMonitorWorks(call);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    mServices.remove(handle);
                }
            };
            try {
                mActivityManagerInternal.startForegroundServiceDelegate(options, fgsConnection);
            } catch (Exception e) {
                Log.i(this, "startForegroundServiceDelegate failed due to: " + e);
            }
        }
    }

    @VisibleForTesting
    public void stopFGSDelegation(PhoneAccountHandle handle) {
        synchronized (mLock) {
            Log.i(this, "stopFGSDelegation of handle %s", handle);
            Set<Call> calls = mPhoneAccountHandleListMap.get(handle);
            for (Call call : calls) {
                stopMonitorWorks(call);
            }
            mPhoneAccountHandleListMap.remove(handle);

            if (mActivityManagerInternal != null) {
                ServiceConnection fgsConnection = mServices.get(handle);
                if (fgsConnection != null) {
                    mActivityManagerInternal.stopForegroundServiceDelegate(fgsConnection);
                }
            }
        }
    }

    private void startMonitorWorks(Call call) {
        startMonitorNotification(call);
    }

    private void stopMonitorWorks(Call call) {
        stopMonitorNotification(call);
    }

    private void startMonitorNotification(Call call) {
        mPendingCalls.add(call);
        mHandler.postDelayed(() -> {
            synchronized (mLock) {
                if (mPendingCalls.contains(call)) {
                    stopFGSDelegation(call.getTargetPhoneAccount());
                    mPendingCalls.remove(call);
                }
            }
        }, 5000L);
    }

    private void stopMonitorNotification(Call call) {
        mPendingCalls.remove(call);
    }

    @VisibleForTesting
    public void setActivityManagerInternal(ActivityManagerInternal ami) {
        mActivityManagerInternal = ami;
    }

    @VisibleForTesting
    public void setNotificationListenerService(NotificationListenerService listener) {
        mNotificationListener = listener;
    }
}
