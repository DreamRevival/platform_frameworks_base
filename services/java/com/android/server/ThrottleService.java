/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.net.IThrottleManager;
import android.net.ThrottleManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.telephony.TelephonyProperties;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Random;

// TODO - add comments - reference the ThrottleManager for public API
public class ThrottleService extends IThrottleManager.Stub {

    private static final String TESTING_ENABLED_PROPERTY = "persist.throttle.testing";

    private static final String TAG = "ThrottleService";
    private static boolean DBG = true;
    private Handler mHandler;
    private HandlerThread mThread;

    private Context mContext;

    private int mPolicyPollPeriodSec;
    private static final int DEFAULT_POLLING_PERIOD_SEC = 60 * 10;
    private static final int TESTING_POLLING_PERIOD_SEC = 60 * 1;

    private static final int TESTING_RESET_PERIOD_SEC = 60 * 3;

    private static final int PERIOD_COUNT = 6;

    private long mPolicyThreshold;
    // TODO - remove testing stuff?
    private static final long DEFAULT_TESTING_THRESHOLD = 1 * 1024 * 1024;
    private static final long DEFAULT_THRESHOLD = 0; // off by default

    private int mPolicyThrottleValue;
    private static final int DEFAULT_THROTTLE_VALUE = 100; // 100 Kbps

    private int mPolicyResetDay; // 1-28

    private long mLastRead; // read byte count from last poll
    private long mLastWrite; // write byte count from last poll

    private static final String ACTION_POLL = "com.android.server.ThrottleManager.action.POLL";
    private static int POLL_REQUEST = 0;
    private PendingIntent mPendingPollIntent;
    private static final String ACTION_RESET = "com.android.server.ThorottleManager.action.RESET";
    private static int RESET_REQUEST = 1;
    private PendingIntent mPendingResetIntent;

    private INetworkManagementService mNMService;
    private AlarmManager mAlarmManager;
    private NotificationManager mNotificationManager;

    private DataRecorder mRecorder;

    private int mThrottleLevel; // 0 for none, 1 for first throttle val, 2 for next, etc

    private String mPolicyIface;

    private static final int NOTIFICATION_WARNING   = 2;
    private static final int NOTIFICATION_ALL       = 0xFFFFFFFF;
    private int mPolicyNotificationsAllowedMask;

    private Notification mThrottlingNotification;
    private boolean mWarningNotificationSent = false;

    public ThrottleService(Context context) {
        if (DBG) Slog.d(TAG, "Starting ThrottleService");
        mContext = context;

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent pollIntent = new Intent(ACTION_POLL, null);
        mPendingPollIntent = PendingIntent.getBroadcast(mContext, POLL_REQUEST, pollIntent, 0);
        Intent resetIntent = new Intent(ACTION_RESET, null);
        mPendingResetIntent = PendingIntent.getBroadcast(mContext, RESET_REQUEST, resetIntent, 0);

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNMService = INetworkManagementService.Stub.asInterface(b);

        mNotificationManager = (NotificationManager)mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ThrottleService");
    }

    public synchronized long getResetTime(String iface) {
        enforceAccessPermission();
        if (iface.equals(mPolicyIface) && (mRecorder != null)) mRecorder.getPeriodEnd();
        return 0;
    }
    public synchronized long getPeriodStartTime(String iface) {
        enforceAccessPermission();
        if (iface.equals(mPolicyIface) && (mRecorder != null)) mRecorder.getPeriodStart();
        return 0;
    }
    //TODO - a better name?  getCliffByteCountThreshold?
    public synchronized long getCliffThreshold(String iface, int cliff) {
        enforceAccessPermission();
        if ((cliff == 0) && iface.equals(mPolicyIface)) {
            return mPolicyThreshold;
        }
        return 0;
    }
    // TODO - a better name? getThrottleRate?
    public synchronized int getCliffLevel(String iface, int cliff) {
        enforceAccessPermission();
        if ((cliff == 0) && iface.equals(mPolicyIface)) {
            return mPolicyThrottleValue;
        }
        return 0;
    }

    public String getHelpUri() {
        enforceAccessPermission();
        return Settings.Secure.getString(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_HELP_URI);
    }

    public synchronized long getByteCount(String iface, int dir, int period, int ago) {
        enforceAccessPermission();
        if (iface.equals(mPolicyIface) &&
                (period == ThrottleManager.PERIOD_CYCLE) &&
                (mRecorder != null)) {
            if (dir == ThrottleManager.DIRECTION_TX) return mRecorder.getPeriodTx(ago);
            if (dir == ThrottleManager.DIRECTION_RX) return mRecorder.getPeriodRx(ago);
        }
        return 0;
    }

    // TODO - a better name - getCurrentThrottleRate?
    public synchronized int getThrottle(String iface) {
        enforceAccessPermission();
        if (iface.equals(mPolicyIface) && (mThrottleLevel == 1)) {
            return mPolicyThrottleValue;
        }
        return 0;
    }

    void systemReady() {
        if (DBG) Slog.d(TAG, "systemReady");
        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mHandler.obtainMessage(EVENT_POLL_ALARM).sendToTarget();
                }
            }, new IntentFilter(ACTION_POLL));

        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mHandler.obtainMessage(EVENT_RESET_ALARM).sendToTarget();
                }
            }, new IntentFilter(ACTION_RESET));

        // use a new thread as we don't want to stall the system for file writes
        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new MyHandler(mThread.getLooper());
        mHandler.obtainMessage(EVENT_REBOOT_RECOVERY).sendToTarget();
    }


    private static final int EVENT_REBOOT_RECOVERY = 0;
    private static final int EVENT_POLICY_CHANGED  = 1;
    private static final int EVENT_POLL_ALARM      = 2;
    private static final int EVENT_RESET_ALARM     = 3;
    private class MyHandler extends Handler {
        public MyHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case EVENT_REBOOT_RECOVERY:
                onRebootRecovery();
                break;
            case EVENT_POLICY_CHANGED:
                onPolicyChanged();
                break;
            case EVENT_POLL_ALARM:
                onPollAlarm();
                break;
            case EVENT_RESET_ALARM:
                onResetAlarm();
            }
        }

        private void onRebootRecovery() {
            if (DBG) Slog.d(TAG, "onRebootRecovery");
            // check for sim change TODO
            // reregister for notification of policy change

            // register for roaming indication change
            // check for roaming TODO

            mRecorder = new DataRecorder(mContext, ThrottleService.this);

            // get policy
            mHandler.obtainMessage(EVENT_POLICY_CHANGED).sendToTarget();

            // evaluate current conditions
            mHandler.obtainMessage(EVENT_POLL_ALARM).sendToTarget();
        }

        private void onSimChange() {
            // TODO
        }

        // check for new policy info (threshold limit/value/etc)
        private void onPolicyChanged() {
            boolean testing = SystemProperties.get(TESTING_ENABLED_PROPERTY).equals("true");

            int pollingPeriod = DEFAULT_POLLING_PERIOD_SEC;
            if (testing) pollingPeriod = TESTING_POLLING_PERIOD_SEC;
            mPolicyPollPeriodSec = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_POLLING_SEC, pollingPeriod);

            // TODO - remove testing stuff?
            long defaultThreshold = DEFAULT_THRESHOLD;
            if (testing) defaultThreshold = DEFAULT_TESTING_THRESHOLD;
            synchronized (ThrottleService.this) {
                mPolicyThreshold = Settings.Secure.getLong(mContext.getContentResolver(),
                        Settings.Secure.THROTTLE_THRESHOLD, defaultThreshold);
                mPolicyThrottleValue = Settings.Secure.getInt(mContext.getContentResolver(),
                        Settings.Secure.THROTTLE_VALUE, DEFAULT_THROTTLE_VALUE);
            }
            mPolicyResetDay = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_RESET_DAY, -1);
            if (mPolicyResetDay == -1 ||
                    ((mPolicyResetDay < 1) || (mPolicyResetDay > 28))) {
                Random g = new Random();
                mPolicyResetDay = 1 + g.nextInt(28); // 1-28
                Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.THROTTLE_RESET_DAY, mPolicyResetDay);
            }
            synchronized (ThrottleService.this) {
                mPolicyIface = Settings.Secure.getString(mContext.getContentResolver(),
                        Settings.Secure.THROTTLE_IFACE);
                // TODO - read default from resource so it's device-specific
                if (mPolicyIface == null) mPolicyIface = "rmnet0";
            }

            mPolicyNotificationsAllowedMask = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.THROTTLE_NOTIFICATION_TYPE, NOTIFICATION_ALL);

            Slog.d(TAG, "onPolicyChanged testing=" + testing +", period=" + mPolicyPollPeriodSec +
                    ", threshold=" + mPolicyThreshold + ", value=" + mPolicyThrottleValue +
                    ", resetDay=" + mPolicyResetDay + ", noteType=" +
                    mPolicyNotificationsAllowedMask);

            Calendar end = calculatePeriodEnd();
            Calendar start = calculatePeriodStart(end);

            mRecorder.setNextPeriod(start,end);

            mAlarmManager.cancel(mPendingResetIntent);
            mAlarmManager.set(AlarmManager.RTC_WAKEUP, end.getTimeInMillis(),
                    mPendingResetIntent);
        }

        private void onPollAlarm() {
            long now = SystemClock.elapsedRealtime();
            long next = now + mPolicyPollPeriodSec*1000;
            long incRead = 0;
            long incWrite = 0;
            try {
                incRead = mNMService.getInterfaceRxCounter(mPolicyIface) - mLastRead;
                incWrite = mNMService.getInterfaceTxCounter(mPolicyIface) - mLastWrite;
            } catch (RemoteException e) {
                Slog.e(TAG, "got remoteException in onPollAlarm:" + e);
            }
            // don't count this data if we're roaming.
            boolean roaming = "true".equals(
                    SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_ISROAMING));
            if (!roaming) {
                mRecorder.addData(incRead, incWrite);
            }

            long periodRx = mRecorder.getPeriodRx(0);
            long periodTx = mRecorder.getPeriodTx(0);
            long total = periodRx + periodTx;
            if (DBG) {
                Slog.d(TAG, "onPollAlarm - now =" + now + ", roaming =" + roaming +
                        ", read =" + incRead + ", written =" + incWrite + ", new total =" + total);
            }
            mLastRead += incRead;
            mLastWrite += incWrite;

            checkThrottleAndPostNotification(total);

            Intent broadcast = new Intent(ThrottleManager.THROTTLE_POLL_ACTION);
            broadcast.putExtra(ThrottleManager.EXTRA_CYCLE_READ, periodRx);
            broadcast.putExtra(ThrottleManager.EXTRA_CYCLE_WRITE, periodTx);
            broadcast.putExtra(ThrottleManager.EXTRA_CYCLE_START, mRecorder.getPeriodStart());
            broadcast.putExtra(ThrottleManager.EXTRA_CYCLE_END, mRecorder.getPeriodEnd());
            mContext.sendStickyBroadcast(broadcast);

            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, mPendingPollIntent);
        }

        private void checkThrottleAndPostNotification(long currentTotal) {
            // are we even doing this?
            if (mPolicyThreshold == 0)
                return;

            // check if we need to throttle
            if (currentTotal > mPolicyThreshold) {
                if (mThrottleLevel != 1) {
                    synchronized (ThrottleService.this) {
                        mThrottleLevel = 1;
                    }
                    if (DBG) Slog.d(TAG, "Threshold " + mPolicyThreshold + " exceeded!");
                    try {
                        mNMService.setInterfaceThrottle(mPolicyIface,
                                mPolicyThrottleValue, mPolicyThrottleValue);
                    } catch (Exception e) {
                        Slog.e(TAG, "error setting Throttle: " + e);
                    }

                    mNotificationManager.cancel(com.android.internal.R.drawable.
                            stat_sys_throttle_warning);

                    postNotification(com.android.internal.R.string.throttled_notification_title,
                            com.android.internal.R.string.throttled_notification_message,
                            com.android.internal.R.drawable.stat_sys_throttled,
                            Notification.FLAG_ONGOING_EVENT);

                    Intent broadcast = new Intent(ThrottleManager.THROTTLE_ACTION);
                    broadcast.putExtra(ThrottleManager.EXTRA_THROTTLE_LEVEL, mPolicyThrottleValue);
                    mContext.sendStickyBroadcast(broadcast);

                } // else already up!
            } else {
                if ((mPolicyNotificationsAllowedMask & NOTIFICATION_WARNING) != 0) {
                    // check if we should warn about throttle
                    if (currentTotal > (mPolicyThreshold/2) && !mWarningNotificationSent) {
                        mWarningNotificationSent = true;
                        mNotificationManager.cancel(com.android.internal.R.drawable.
                                stat_sys_throttle_warning);
                        postNotification(com.android.internal.R.string.
                                throttle_warning_notification_title,
                                com.android.internal.R.string.
                                throttle_warning_notification_message,
                                com.android.internal.R.drawable.stat_sys_throttle_warning,
                                0);
                    } else {
                        mWarningNotificationSent =false;
                    }
                }
            }
        }

        private void postNotification(int titleInt, int messageInt, int icon, int flags) {
            Intent intent = new Intent();
            // TODO - fix up intent
            intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
            intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

            PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

            Resources r = Resources.getSystem();
            CharSequence title = r.getText(titleInt);
            CharSequence message = r.getText(messageInt);
            if (mThrottlingNotification == null) {
                mThrottlingNotification = new Notification();
                mThrottlingNotification.when = 0;
                // TODO -  fixup icon
                mThrottlingNotification.icon = icon;
                mThrottlingNotification.defaults &= ~Notification.DEFAULT_SOUND;
            }
            mThrottlingNotification.flags = flags;
            mThrottlingNotification.tickerText = title;
            mThrottlingNotification.setLatestEventInfo(mContext, title, message, pi);

            mNotificationManager.notify(mThrottlingNotification.icon, mThrottlingNotification);
        }


        private synchronized void clearThrottleAndNotification() {
            if (mThrottleLevel == 1) {
                synchronized (ThrottleService.this) {
                    mThrottleLevel = 0;
                }
                try {
                    mNMService.setInterfaceThrottle(mPolicyIface, -1, -1);
                } catch (Exception e) {
                    Slog.e(TAG, "error clearing Throttle: " + e);
                }
                Intent broadcast = new Intent(ThrottleManager.THROTTLE_ACTION);
                broadcast.putExtra(ThrottleManager.EXTRA_THROTTLE_LEVEL, -1);
                mContext.sendStickyBroadcast(broadcast);
            }
            mNotificationManager.cancel(com.android.internal.R.drawable.stat_sys_throttle_warning);
            mNotificationManager.cancel(com.android.internal.R.drawable.stat_sys_throttled);
            mWarningNotificationSent = false;
        }

        private Calendar calculatePeriodEnd() {
            Calendar end = GregorianCalendar.getInstance();
            int day = end.get(Calendar.DAY_OF_MONTH);
            end.set(Calendar.DAY_OF_MONTH, mPolicyResetDay);
            end.set(Calendar.HOUR_OF_DAY, 0);
            end.set(Calendar.MINUTE, 0);
            if (day >= mPolicyResetDay) {
                int month = end.get(Calendar.MONTH);
                if (month == Calendar.DECEMBER) {
                    end.set(Calendar.YEAR, end.get(Calendar.YEAR) + 1);
                    month = Calendar.JANUARY - 1;
                }
                end.set(Calendar.MONTH, month + 1);
            }

            // TODO - remove!
            if (SystemProperties.get(TESTING_ENABLED_PROPERTY).equals("true")) {
                end = GregorianCalendar.getInstance();
                end.add(Calendar.SECOND, TESTING_RESET_PERIOD_SEC);
            }
            return end;
        }
        private Calendar calculatePeriodStart(Calendar end) {
            Calendar start = (Calendar)end.clone();
            int month = end.get(Calendar.MONTH);
            if (end.get(Calendar.MONTH) == Calendar.JANUARY) {
                month = Calendar.DECEMBER + 1;
                start.set(Calendar.YEAR, start.get(Calendar.YEAR) - 1);
            }
            start.set(Calendar.MONTH, month - 1);

            // TODO - remove!!
            if (SystemProperties.get(TESTING_ENABLED_PROPERTY).equals("true")) {
                start = (Calendar)end.clone();
                start.add(Calendar.SECOND, -TESTING_RESET_PERIOD_SEC);
            }
            return start;
        }

        private void onResetAlarm() {
            if (DBG) {
                Slog.d(TAG, "onResetAlarm - last period had " + mRecorder.getPeriodRx(0) +
                        " bytes read and " + mRecorder.getPeriodTx(0) + " written");
            }

            Calendar end = calculatePeriodEnd();
            Calendar start = calculatePeriodStart(end);

            clearThrottleAndNotification();

            mRecorder.setNextPeriod(start,end);

            mAlarmManager.set(AlarmManager.RTC_WAKEUP, end.getTimeInMillis(),
                    mPendingResetIntent);
        }
    }

    // records bytecount data for a given time and accumulates it into larger time windows
    // for logging and other purposes
    //
    // since time can be changed (user or network action) we will have to track the time of the
    // last recording and deal with it.
    private static class DataRecorder {
        long[] mPeriodRxData;
        long[] mPeriodTxData;
        int mCurrentPeriod;
        int mPeriodCount;

        Calendar mPeriodStart;
        Calendar mPeriodEnd;

        ThrottleService mParent;
        Context mContext;
        SharedPreferences mSharedPreferences;

        DataRecorder(Context context, ThrottleService parent) {
            mContext = context;
            mParent = parent;

            synchronized (mParent) {
                mPeriodCount = 6;
                mPeriodRxData = new long[mPeriodCount];
                mPeriodTxData = new long[mPeriodCount];

                mPeriodStart = Calendar.getInstance();
                mPeriodEnd = Calendar.getInstance();

                mSharedPreferences = mContext.getSharedPreferences("ThrottleData",
                        android.content.Context.MODE_PRIVATE);

                zeroData(0);
                retrieve();
            }
        }

        void setNextPeriod(Calendar start, Calendar end) {
            if (DBG) {
                Slog.d(TAG, "setting next period to " + start.getTimeInMillis() +
                        " --until-- " + end.getTimeInMillis());
            }
            // if we roll back in time to a previous period, toss out the current data
            // if we roll forward to the next period, advance to the next

            if (end.before(mPeriodStart)) {
                if (DBG) {
                    Slog.d(TAG, " old start was " + mPeriodStart.getTimeInMillis() + ", wiping");
                }
                synchronized (mParent) {
                    mPeriodRxData[mCurrentPeriod] = 0;
                    mPeriodTxData[mCurrentPeriod] = 0;
                }
            } else if(start.after(mPeriodEnd)) {
                if (DBG) {
                    Slog.d(TAG, " old end was " + mPeriodEnd.getTimeInMillis() + ", following");
                }
                synchronized (mParent) {
                    ++mCurrentPeriod;
                    if (mCurrentPeriod >= mPeriodCount) mCurrentPeriod = 0;
                    mPeriodRxData[mCurrentPeriod] = 0;
                    mPeriodTxData[mCurrentPeriod] = 0;
                }
            } else {
                if (DBG) Slog.d(TAG, " we fit - ammending to last period");
            }
            setPeriodStart(start);
            setPeriodEnd(end);
            record();
        }

        public long getPeriodEnd() {
            synchronized (mParent) {
                return mPeriodEnd.getTimeInMillis();
            }
        }

        private void setPeriodEnd(Calendar end) {
            synchronized (mParent) {
                mPeriodEnd = end;
            }
        }

        public long getPeriodStart() {
            synchronized (mParent) {
                return mPeriodStart.getTimeInMillis();
            }
        }

        private void setPeriodStart(Calendar start) {
            synchronized (mParent) {
                mPeriodStart = start;
            }
        }

        public int getPeriodCount() {
            synchronized (mParent) {
                return mPeriodCount;
            }
        }

        private void zeroData(int field) {
            synchronized (mParent) {
                for(int period = 0; period<mPeriodCount; period++) {
                    mPeriodRxData[period] = 0;
                    mPeriodTxData[period] = 0;
                }
                mCurrentPeriod = 0;
            }

        }

        // if time moves backward accumulate all read/write that's lost into the now
        // otherwise time moved forward.
        void addData(long bytesRead, long bytesWritten) {
            synchronized (mParent) {
                mPeriodRxData[mCurrentPeriod] += bytesRead;
                mPeriodTxData[mCurrentPeriod] += bytesWritten;
            }
            record();
        }

        private void record() {
            // serialize into a secure setting

            // 1 int mPeriodCount
            // 13*6 long[PERIOD_COUNT] mPeriodRxData
            // 13*6 long[PERIOD_COUNT] mPeriodTxData
            // 1  int mCurrentPeriod
            // 13 long periodStartMS
            // 13 long periodEndMS
            // 199 chars max
            StringBuilder builder = new StringBuilder();
            builder.append(mPeriodCount);
            builder.append(":");
            for(int i=0; i < mPeriodCount; i++) {
                builder.append(mPeriodRxData[i]);
                builder.append(":");
            }
            for(int i=0; i < mPeriodCount; i++) {
                builder.append(mPeriodTxData[i]);
                builder.append(":");
            }
            builder.append(mCurrentPeriod);
            builder.append(":");
            builder.append(mPeriodStart.getTimeInMillis());
            builder.append(":");
            builder.append(mPeriodEnd.getTimeInMillis());
            builder.append(":");

            SharedPreferences.Editor editor = mSharedPreferences.edit();

            editor.putString("Data", builder.toString());
            editor.commit();
        }

        private void retrieve() {
            String data = mSharedPreferences.getString("Data", "");
//            String data = Settings.Secure.getString(mContext.getContentResolver(),
//                    Settings.Secure.THROTTLE_VALUE);
            if (data == null || data.length() == 0) return;

            synchronized (mParent) {
                String[] parsed = data.split(":");
                int parsedUsed = 0;
                if (parsed.length < 6) return;

                mPeriodCount = Integer.parseInt(parsed[parsedUsed++]);
                if (parsed.length != 4 + (2 * mPeriodCount)) return;

                mPeriodRxData = new long[mPeriodCount];
                for(int i=0; i < mPeriodCount; i++) {
                    mPeriodRxData[i] = Long.parseLong(parsed[parsedUsed++]);
                }
                mPeriodTxData = new long[mPeriodCount];
                for(int i=0; i < mPeriodCount; i++) {
                    mPeriodTxData[i] = Long.parseLong(parsed[parsedUsed++]);
                }
                mCurrentPeriod = Integer.parseInt(parsed[parsedUsed++]);
                mPeriodStart = new GregorianCalendar();
                mPeriodStart.setTimeInMillis(Long.parseLong(parsed[parsedUsed++]));
                mPeriodEnd = new GregorianCalendar();
                mPeriodEnd.setTimeInMillis(Long.parseLong(parsed[parsedUsed++]));
            }
        }

        long getPeriodRx(int which) {
            synchronized (mParent) {
                if (which > mPeriodCount) return 0;
                which = mCurrentPeriod - which;
                if (which < 0) which += mPeriodCount;
                return mPeriodRxData[which];
            }
        }
        long getPeriodTx(int which) {
            synchronized (mParent) {
                if (which > mPeriodCount) return 0;
                which = mCurrentPeriod - which;
                if (which < 0) which += mPeriodCount;
                return mPeriodTxData[which];
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ThrottleService " +
                    "from from pid=" + Binder.getCallingPid() + ", uid=" +
                    Binder.getCallingUid());
            return;
        }
        pw.println();

        pw.println("The threshold is " + mPolicyThreshold +
                ", after which you experince throttling to " +
                mPolicyThrottleValue + "kbps");
        pw.println("Current period is " +
                (mRecorder.getPeriodEnd() - mRecorder.getPeriodStart())/1000 + " seconds long " +
                "and ends in " + (mRecorder.getPeriodEnd() - System.currentTimeMillis()) / 1000 +
                " seconds.");
        pw.println("Polling every " + mPolicyPollPeriodSec + " seconds");
        for (int i = 0; i < mRecorder.getPeriodCount(); i++) {
            pw.println(" Period[" + i + "] - read:" + mRecorder.getPeriodRx(i) + ", written:" +
                    mRecorder.getPeriodTx(i));
        }
    }
}