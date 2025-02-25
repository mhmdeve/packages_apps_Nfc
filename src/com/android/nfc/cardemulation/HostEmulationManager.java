/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.nfc.cardemulation;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.nfc.NfcService;
import com.android.nfc.NfcStatsLog;
import com.android.nfc.cardemulation.RegisteredAidCache.AidResolveInfo;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class HostEmulationManager {
    static final String TAG = "HostEmulationManager";
    static final boolean DBG = SystemProperties.getBoolean("persist.nfc.debug_enabled", false);

    static final int STATE_IDLE = 0;
    static final int STATE_W4_SELECT = 1;
    static final int STATE_W4_SERVICE = 2;
    static final int STATE_W4_DEACTIVATE = 3;
    static final int STATE_XFER = 4;

    /** Minimum AID lenth as per ISO7816 */
    static final int MINIMUM_AID_LENGTH = 5;

    /** Length of Select APDU header including length byte */
    static final int SELECT_APDU_HDR_LENGTH = 5;

    static final byte INSTR_SELECT = (byte)0xA4;

    static final String ANDROID_HCE_AID = "A000000476416E64726F6964484345";
    static final byte[] ANDROID_HCE_RESPONSE = {0x14, (byte)0x81, 0x00, 0x00, (byte)0x90, 0x00};

    static final byte[] AID_NOT_FOUND = {0x6A, (byte)0x82};
    static final byte[] UNKNOWN_ERROR = {0x6F, 0x00};

    final Context mContext;
    final RegisteredAidCache mAidCache;
    final Messenger mMessenger = new Messenger (new MessageHandler());
    final KeyguardManager mKeyguard;
    final Object mLock;
    final PowerManager mPowerManager;

    // All variables below protected by mLock

    // Variables below are for a non-payment service,
    // that is typically only bound in the STATE_XFER state.
    Messenger mService;
    boolean mServiceBound = false;
    ComponentName mServiceName = null;
    int mServiceUserId; // The UserId of the non-payment service

    // Variables below are for a payment service,
    // which is typically bound persistently to improve on
    // latency.
    Messenger mPaymentService;
    boolean mPaymentServiceBound = false;
    ComponentName mPaymentServiceName = null;
    int mPaymentServiceUserId; // The userId of the payment service
    ComponentName mLastBoundPaymentServiceName;

    // mActiveService denotes the service interface
    // that is the current active one, until a new SELECT AID
    // comes in that may be resolved to a different service.
    // On deactivation, mActiveService stops being valid.
    Messenger mActiveService;
    ComponentName mActiveServiceName;
    int mActiveServiceUserId; // The UserId of the current active one

    String mLastSelectedAid;
    int mState;
    byte[] mSelectApdu;

    public HostEmulationManager(Context context, RegisteredAidCache aidCache) {
        mContext = context;
        mLock = new Object();
        mAidCache = aidCache;
        mState = STATE_IDLE;
        mKeyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    /**
     *  Preferred payment service changed
     */
    public void onPreferredPaymentServiceChanged(int userId, final ComponentName service) {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (mLock) {
                if (service != null) {
                    bindPaymentServiceLocked(userId, service);
                } else {
                    unbindPaymentServiceLocked();
                }
            }
        });
    }

    /**
     *  Preferred foreground service changed
     */
    public void onPreferredForegroundServiceChanged(int userId, ComponentName service) {
        synchronized (mLock) {
            if (service != null) {
                bindServiceIfNeededLocked(userId, service);
            } else {
                unbindServiceIfNeededLocked();
            }
         }
     }

    public void onHostEmulationActivated() {
        Log.d(TAG, "notifyHostEmulationActivated");
        synchronized (mLock) {
            // Regardless of what happens, if we're having a tap again
            // activity up, close it
            Intent intent = new Intent(TapAgainDialog.ACTION_CLOSE);
            intent.setPackage("com.android.nfc");
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            if (mState != STATE_IDLE) {
                Log.e(TAG, "Got activation event in non-idle state");
            }
            mState = STATE_W4_SELECT;
        }
    }

    public void onHostEmulationData(byte[] data) {
        Log.d(TAG, "notifyHostEmulationData");
        String selectAid = findSelectAid(data);
        ComponentName resolvedService = null;
        ApduServiceInfo resolvedServiceInfo = null;
        AidResolveInfo resolveInfo = null;
        synchronized (mLock) {
            if (mState == STATE_IDLE) {
                Log.e(TAG, "Got data in idle state.");
                return;
            } else if (mState == STATE_W4_DEACTIVATE) {
                Log.e(TAG, "Dropping APDU in STATE_W4_DECTIVATE");
                return;
            }
            if (selectAid != null) {
                if (selectAid.equals(ANDROID_HCE_AID)) {
                    NfcService.getInstance().sendData(ANDROID_HCE_RESPONSE);
                    return;
                }
                resolveInfo = mAidCache.resolveAid(selectAid);
                if (resolveInfo == null || resolveInfo.services.size() == 0) {
                    // Tell the remote we don't handle this AID
                    NfcService.getInstance().sendData(AID_NOT_FOUND);
                    return;
                }
                mLastSelectedAid = selectAid;
                if (resolveInfo.defaultService != null) {
                    // Resolve to default
                    // Check if resolvedService requires unlock
                    ApduServiceInfo defaultServiceInfo = resolveInfo.defaultService;
                    if (defaultServiceInfo.requiresUnlock() && mKeyguard.isKeyguardLocked()) {
                        NfcService.getInstance().sendRequireUnlockIntent();
                        NfcService.getInstance().sendData(AID_NOT_FOUND);
                        if (DBG) Log.d(TAG, "requiresUnlock()! show toast");
                        launchTapAgain(resolveInfo.defaultService, resolveInfo.category);
                        return;
                    }
                    if (defaultServiceInfo.requiresScreenOn() && !mPowerManager.isScreenOn()) {
                        NfcService.getInstance().sendRequireUnlockIntent();
                        NfcService.getInstance().sendData(AID_NOT_FOUND);
                        if (DBG) Log.d(TAG, "requiresScreenOn()!");
                        return;
                    }
                    // In no circumstance should this be an OffHostService -
                    // we should never get this AID on the host in the first place
                    if (!defaultServiceInfo.isOnHost()) {
                        Log.e(TAG, "AID that was meant to go off-host was routed to host." +
                                " Check routing table configuration.");
                        NfcService.getInstance().sendData(AID_NOT_FOUND);
                        return;
                    }
                    resolvedService = defaultServiceInfo.getComponent();
                    resolvedServiceInfo = defaultServiceInfo;
                } else if (mActiveServiceName != null) {
                    for (ApduServiceInfo serviceInfo : resolveInfo.services) {
                        if (mActiveServiceName.equals(serviceInfo.getComponent())) {
                            resolvedService = mActiveServiceName;
                            resolvedServiceInfo = serviceInfo;
                            break;
                        }
                    }
                }
                if (resolvedService == null) {
                    // We have no default, and either one or more services.
                    // Ask the user to confirm.
                    // Just ignore all future APDUs until we resolve to only one
                    mState = STATE_W4_DEACTIVATE;
                    launchResolver((ArrayList<ApduServiceInfo>)resolveInfo.services, null,
                            resolveInfo.category);
                    return;
                }
            }
            switch (mState) {
                case STATE_W4_SELECT:
                    if (selectAid != null) {
                        UserHandle user =
                                UserHandle.getUserHandleForUid(resolvedServiceInfo.getUid());
                        Messenger existingService =
                                bindServiceIfNeededLocked(user.getIdentifier(), resolvedService);
                        if (existingService != null) {
                            Log.d(TAG, "Binding to existing service");
                            mState = STATE_XFER;
                            sendDataToServiceLocked(existingService, data);
                        } else {
                            // Waiting for service to be bound
                            Log.d(TAG, "Waiting for new service.");
                            // Queue SELECT APDU to be used
                            mSelectApdu = data;
                            mState = STATE_W4_SERVICE;
                        }
                        if (CardEmulation.CATEGORY_PAYMENT.equals(resolveInfo.category)) {
                            NfcStatsLog.write(NfcStatsLog.NFC_CARDEMULATION_OCCURRED,
                                    NfcStatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__HCE_PAYMENT,
                                    "HCE");
                        } else {
                            NfcStatsLog.write(NfcStatsLog.NFC_CARDEMULATION_OCCURRED,
                                    NfcStatsLog.NFC_CARDEMULATION_OCCURRED__CATEGORY__HCE_OTHER,
                                    "HCE");
                        }
                    } else {
                        Log.d(TAG, "Dropping non-select APDU in STATE_W4_SELECT");
                        NfcService.getInstance().sendData(UNKNOWN_ERROR);
                    }
                    break;
                case STATE_W4_SERVICE:
                    Log.d(TAG, "Unexpected APDU in STATE_W4_SERVICE");
                    break;
                case STATE_XFER:
                    if (selectAid != null) {
                        UserHandle user =
                                UserHandle.getUserHandleForUid(resolvedServiceInfo.getUid());
                        Messenger existingService =
                                bindServiceIfNeededLocked(user.getIdentifier(), resolvedService);
                        if (existingService != null) {
                            sendDataToServiceLocked(existingService, data);
                            mState = STATE_XFER;
                        } else {
                            // Waiting for service to be bound
                            mSelectApdu = data;
                            mState = STATE_W4_SERVICE;
                        }
                    } else if (mActiveService != null) {
                        // Regular APDU data
                        sendDataToServiceLocked(mActiveService, data);
                    } else {
                        // No SELECT AID and no active service.
                        Log.d(TAG, "Service no longer bound, dropping APDU");
                    }
                    break;
            }
        }
    }

    public void onHostEmulationDeactivated() {
        Log.d(TAG, "notifyHostEmulationDeactivated");
        synchronized (mLock) {
            if (mState == STATE_IDLE) {
                Log.e(TAG, "Got deactivation event while in idle state");
            }
            sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_LINK_LOSS);
            mActiveService = null;
            mActiveServiceName = null;
            mActiveServiceUserId = -1;
            unbindServiceIfNeededLocked();
            mState = STATE_IDLE;
        }
    }

    public void onOffHostAidSelected() {
        Log.d(TAG, "notifyOffHostAidSelected");
        synchronized (mLock) {
            if (mState != STATE_XFER || mActiveService == null) {
                // Don't bother telling, we're not bound to any service yet
            } else {
                sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_DESELECTED);
            }
            mActiveService = null;
            mActiveServiceName = null;
            mActiveServiceUserId = -1;
            unbindServiceIfNeededLocked();
            mState = STATE_W4_SELECT;

            //close the TapAgainDialog
            Intent intent = new Intent(TapAgainDialog.ACTION_CLOSE);
            intent.setPackage("com.android.nfc");
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    Messenger bindServiceIfNeededLocked(int userId, ComponentName service) {
        if (mPaymentServiceName != null && mPaymentServiceName.equals(service)
                && mPaymentServiceUserId == userId) {
            Log.d(TAG, "Service already bound as payment service.");
            return mPaymentService;
        } else if (mServiceName != null && mServiceName.equals(service)
                && mServiceUserId == userId) {
            Log.d(TAG, "Service already bound as regular service.");
            return mService;
        } else {
            Log.d(TAG, "Binding to service " + service);
            unbindServiceIfNeededLocked();
            Intent aidIntent = new Intent(HostApduService.SERVICE_INTERFACE);
            aidIntent.setComponent(service);
            try {
                mServiceBound = mContext.bindServiceAsUser(aidIntent, mConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                        UserHandle.of(userId));
                if (!mServiceBound) {
                    Log.e(TAG, "Could not bind service.");
                } else {
                    mServiceUserId = userId;
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Could not bind service due to security exception.");
            }
            return null;
        }
    }

    void sendDataToServiceLocked(Messenger service, byte[] data) {
        if (service != mActiveService) {
            sendDeactivateToActiveServiceLocked(HostApduService.DEACTIVATION_DESELECTED);
            mActiveService = service;
            if (service.equals(mPaymentService)) {
                mActiveServiceName = mPaymentServiceName;
                mActiveServiceUserId = mPaymentServiceUserId;
            } else {
                mActiveServiceName = mServiceName;
                mActiveServiceUserId = mServiceUserId;
            }
        }
        Message msg = Message.obtain(null, HostApduService.MSG_COMMAND_APDU);
        Bundle dataBundle = new Bundle();
        dataBundle.putByteArray("data", data);
        msg.setData(dataBundle);
        msg.replyTo = mMessenger;
        try {
            mActiveService.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote service has died, dropping APDU");
        }
    }

    void sendDeactivateToActiveServiceLocked(int reason) {
        if (mActiveService == null) return;
        Message msg = Message.obtain(null, HostApduService.MSG_DEACTIVATED);
        msg.arg1 = reason;
        try {
            mActiveService.send(msg);
        } catch (RemoteException e) {
            // Don't care
        }
    }

    void unbindPaymentServiceLocked() {
        if (mPaymentServiceBound) {
            mContext.unbindService(mPaymentConnection);
            mPaymentServiceBound = false;
            mPaymentService = null;
            mPaymentServiceName = null;
            mPaymentServiceUserId = -1;
        }
    }

    void bindPaymentServiceLocked(int userId, ComponentName service) {
        unbindPaymentServiceLocked();

        Intent intent = new Intent(HostApduService.SERVICE_INTERFACE);
        intent.setComponent(service);
        if (mContext.bindServiceAsUser(intent, mPaymentConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                UserHandle.of(userId))) {
            mPaymentServiceBound = true;
            mPaymentServiceUserId = userId;
            mLastBoundPaymentServiceName = service;
        } else {
            Log.e(TAG, "Could not bind (persistent) payment service.");
        }
    }

    void unbindServiceIfNeededLocked() {
        if (mServiceBound) {
            Log.d(TAG, "Unbinding from service " + mServiceName);
            mContext.unbindService(mConnection);
            mServiceBound = false;
            mService = null;
            mServiceName = null;
            mServiceUserId = -1;
        }
    }

    void launchTapAgain(ApduServiceInfo service, String category) {
        Intent dialogIntent = new Intent(mContext, TapAgainDialog.class);
        dialogIntent.putExtra(TapAgainDialog.EXTRA_CATEGORY, category);
        dialogIntent.putExtra(TapAgainDialog.EXTRA_APDU_SERVICE, service);
        dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivityAsUser(dialogIntent,
                UserHandle.getUserHandleForUid(service.getUid()));
    }

    void launchResolver(ArrayList<ApduServiceInfo> services, ComponentName failedComponent,
            String category) {
        Intent intent = new Intent(mContext, AppChooserActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putParcelableArrayListExtra(AppChooserActivity.EXTRA_APDU_SERVICES, services);
        intent.putExtra(AppChooserActivity.EXTRA_CATEGORY, category);
        if (failedComponent != null) {
            intent.putExtra(AppChooserActivity.EXTRA_FAILED_COMPONENT, failedComponent);
        }
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    String findSelectAid(byte[] data) {
        if (data == null || data.length < SELECT_APDU_HDR_LENGTH + MINIMUM_AID_LENGTH) {
            if (DBG) Log.d(TAG, "Data size too small for SELECT APDU");
            return null;
        }
        // To accept a SELECT AID for dispatch, we require the following:
        // Class byte must be 0x00: logical channel set to zero, no secure messaging, no chaining
        // Instruction byte must be 0xA4: SELECT instruction
        // P1: must be 0x04: select by application identifier
        // P2: File control information is only relevant for higher-level application,
        //     and we only support "first or only occurrence".
        if (data[0] == 0x00 && data[1] == INSTR_SELECT && data[2] == 0x04) {
            if (data[3] != 0x00) {
                Log.d(TAG, "Selecting next, last or previous AID occurrence is not supported");
            }
            int aidLength = data[4];
            if (data.length < SELECT_APDU_HDR_LENGTH + aidLength) {
                return null;
            }
            return bytesToString(data, SELECT_APDU_HDR_LENGTH, aidLength);
        }
        return null;
    }

    private ServiceConnection mPaymentConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                /* Preferred Payment Service has been changed. */
                if (!mLastBoundPaymentServiceName.equals(name)) {
                    return;
                }
                mPaymentServiceName = name;
                mPaymentService = new Messenger(service);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mPaymentService = null;
                mPaymentServiceBound = false;
                mPaymentServiceName = null;
                mPaymentServiceUserId = -1;
            }
        }
    };

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                /* Service is already deactivated, don't bind */
                if (mState == STATE_IDLE) {
                  return;
                }
                mService = new Messenger(service);
                mServiceName = name;
                mServiceBound = true;
                Log.d(TAG, "Service bound");
                mState = STATE_XFER;
                // Send pending select APDU
                if (mSelectApdu != null) {
                    sendDataToServiceLocked(mService, mSelectApdu);
                    mSelectApdu = null;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                Log.d(TAG, "Service unbound");
                mService = null;
                mServiceName = null;
                mServiceBound = false;
                mServiceUserId = -1;
            }
        }
    };

    class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            synchronized(mLock) {
                if (mActiveService == null) {
                    Log.d(TAG, "Dropping service response message; service no longer active.");
                    return;
                } else if (!msg.replyTo.getBinder().equals(mActiveService.getBinder())) {
                    Log.d(TAG, "Dropping service response message; service no longer bound.");
                    return;
                }
            }
            if (msg.what == HostApduService.MSG_RESPONSE_APDU) {
                Bundle dataBundle = msg.getData();
                if (dataBundle == null) {
                    return;
                }
                byte[] data = dataBundle.getByteArray("data");
                if (data == null || data.length == 0) {
                    Log.e(TAG, "Dropping empty R-APDU");
                    return;
                }
                int state;
                synchronized(mLock) {
                    state = mState;
                }
                if (state == STATE_XFER) {
                    Log.d(TAG, "Sending data");
                    NfcService.getInstance().sendData(data);
                } else {
                    Log.d(TAG, "Dropping data, wrong state " + Integer.toString(state));
                }
            } else if (msg.what == HostApduService.MSG_UNHANDLED) {
                synchronized (mLock) {
                    AidResolveInfo resolveInfo = mAidCache.resolveAid(mLastSelectedAid);
                    boolean isPayment = false;
                    if (resolveInfo.services.size() > 0) {
                        launchResolver((ArrayList<ApduServiceInfo>)resolveInfo.services,
                                mActiveServiceName, resolveInfo.category);
                    }
                }
            }
        }
    }

    static String bytesToString(byte[] bytes, int offset, int length) {
        final char[] hexChars = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] chars = new char[length * 2];
        int byteValue;
        for (int j = 0; j < length; j++) {
            byteValue = bytes[offset + j] & 0xFF;
            chars[j * 2] = hexChars[byteValue >>> 4];
            chars[j * 2 + 1] = hexChars[byteValue & 0x0F];
        }
        return new String(chars);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Bound HCE-A/HCE-B services: ");
        if (mPaymentServiceBound) {
            pw.println("    payment: " + mPaymentServiceName);
        }
        if (mServiceBound) {
            pw.println("    other: " + mServiceName);
        }
    }

    /**
     * Dump debugging information as a HostEmulationManagerProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    void dumpDebug(ProtoOutputStream proto) {
        if (mPaymentServiceBound) {
            mPaymentServiceName.dumpDebug(proto, HostEmulationManagerProto.PAYMENT_SERVICE_NAME);
        }
        if (mServiceBound) {
            mServiceName.dumpDebug(proto, HostEmulationManagerProto.SERVICE_NAME);
        }
    }
}
