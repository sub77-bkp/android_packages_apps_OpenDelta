/* 
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2014 The OmniROM Project
 */
/* 
 * This file is part of OpenDelta.
 * 
 * OpenDelta is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * OpenDelta is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with OpenDelta. If not, see <http://www.gnu.org/licenses/>.
 */
/*
 * We're using three different alarms for scheduling. The primary is an
 * (inexact) interval alarm that is fired every 30-60 minutes (if the device 
 * is already awake anyway) to see if conditions are right to automatically 
 * check for updates. 
 * 
 * The second alarm is a backup (inexact) alarm that will actually wake up 
 * the device every few hours (if our interval alarm has not been fired 
 * because of no background activity). Because this only happens once every 
 * 3-6 hours and Android will attempt to schedule it together with other 
 * wakeups, effect on battery life should be completely insignificant. 
 *  
 * Last but not least, we're using an (exact) alarm that will fire if the
 * screen has been off for 5.5 hours. The idea is that you might be asleep
 * at this time and will wake up soon-ish, and we would not mind surprising
 * you with a fresh nightly.
 * 
 * The first two alarms only request a check for updates if the previous
 * check was 6 hours or longer ago. The last alarm will request that check
 * regardless. Regardless of those parameters, the update service will still
 * only perform the actual check if it's happy with the current network
 * (Wi-Fi) and battery (charging / juice aplenty) state. 
 */

package eu.chainfire.opendelta;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import eu.chainfire.opendelta.ScreenState.OnScreenStateListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Scheduler
        implements
        OnScreenStateListener
{
    public interface OnWantUpdateCheckListener {
        public boolean onWantUpdateCheck();
    }

    private static final String PREF_LAST_CHECK_ATTEMPT_TIME_NAME = "last_check_attempt_time";
    private static final long PREF_LAST_CHECK_ATTEMPT_TIME_DEFAULT = 0L;

    private static final long CHECK_THRESHOLD_MS = 6 * AlarmManager.INTERVAL_HOUR;
    private static final long ALARM_INTERVAL_START = AlarmManager.INTERVAL_FIFTEEN_MINUTES;
    private static final long ALARM_INTERVAL_INTERVAL = AlarmManager.INTERVAL_HALF_HOUR;
    private static final long ALARM_SECONDARY_WAKEUP_TIME = 3 * AlarmManager.INTERVAL_HOUR;
    private static final long ALARM_DETECT_SLEEP_TIME = (5 * AlarmManager.INTERVAL_HOUR)
            + AlarmManager.INTERVAL_HALF_HOUR;

    private OnWantUpdateCheckListener onWantUpdateCheckListener = null;
    private AlarmManager alarmManager = null;
    private SharedPreferences prefs = null;

    private PendingIntent alarmInterval = null;
    private PendingIntent alarmSecondaryWake = null;
    private PendingIntent alarmDetectSleep = null;

    private SimpleDateFormat sdfLog = (new SimpleDateFormat("HH:mm", Locale.ENGLISH));

    public Scheduler(Context context, OnWantUpdateCheckListener onWantUpdateCheckListener) {
        this.onWantUpdateCheckListener = onWantUpdateCheckListener;
        alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);

        alarmInterval = UpdateService.alarmPending(context, 1);
        alarmSecondaryWake = UpdateService.alarmPending(context, 2);
        alarmDetectSleep = UpdateService.alarmPending(context, 3);

        alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + ALARM_INTERVAL_START,
                ALARM_INTERVAL_INTERVAL,
                alarmInterval
        );

        setSecondaryWakeAlarm();
    }

    private void setSecondaryWakeAlarm() {
        Logger.i("Setting secondary alarm (inexact) for %s",
                sdfLog.format(new Date(System.currentTimeMillis() + ALARM_SECONDARY_WAKEUP_TIME)));
        alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_SECONDARY_WAKEUP_TIME,
                alarmSecondaryWake
        );
    }

    private void cancelSecondaryWakeAlarm() {
        Logger.i("Cancelling secondary alarm");
        alarmManager.cancel(alarmSecondaryWake);
    }

    private void setDetectSleepAlarm() {
        Logger.i("Setting sleep detection alarm (exact) for %s",
                sdfLog.format(new Date(System.currentTimeMillis() + ALARM_DETECT_SLEEP_TIME)));
        alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_DETECT_SLEEP_TIME,
                alarmDetectSleep
        );
    }

    private void cancelDetectSleepAlarm() {
        Logger.i("Cancelling sleep detection alarm");
        alarmManager.cancel(alarmDetectSleep);
    }

    @Override
    public void onScreenState(boolean state) {
        if (!state) {
            setDetectSleepAlarm();
        } else {
            cancelDetectSleepAlarm();
        }
    }

    private boolean checkForUpdates(boolean force) {
        // Using abs here in case user changes date/time
        if (force
                || (Math.abs(System.currentTimeMillis()
                        - prefs.getLong(PREF_LAST_CHECK_ATTEMPT_TIME_NAME,
                                PREF_LAST_CHECK_ATTEMPT_TIME_DEFAULT)) > CHECK_THRESHOLD_MS)) {
            if (onWantUpdateCheckListener != null) {
                if (onWantUpdateCheckListener.onWantUpdateCheck()) {
                    prefs.edit()
                            .putLong(PREF_LAST_CHECK_ATTEMPT_TIME_NAME, System.currentTimeMillis())
                            .commit();
                }
            }
        }
        return false;
    }

    public void alarm(int id) {
        switch (id) {
            case 1:
                // This is the interval alarm, called only if the device is
                // already awake for some reason. Might as well see if
                // conditions match to check for updates, right ?
                Logger.i("Interval alarm fired");
                checkForUpdates(false);
                break;

            case 2:
                // Fallback alarm. Our interval alarm has not been called for
                // several hours. The device might have been woken up just
                // for us. Let's see if conditions are good to check for
                // updates.
                Logger.i("Secondary alarm fired");
                checkForUpdates(false);
                break;

            case 3:
                // The screen has been off for 5:30 hours, with luck we've
                // caught the user asleep and we'll have a fresh build waiting
                // when (s)he wakes!
                Logger.i("Sleep detection alarm fired");
                checkForUpdates(true);
                break;
        }

        // Reset fallback wakeup command, we don't need to be called for another
        // few hours
        cancelSecondaryWakeAlarm();
        setSecondaryWakeAlarm();
    }
}
