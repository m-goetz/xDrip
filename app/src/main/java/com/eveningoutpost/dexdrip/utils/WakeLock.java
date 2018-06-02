package com.eveningoutpost.dexdrip.utils;

import android.content.Context;
import android.os.PowerManager;

/**
 * Created by maximilian on 23.02.18.
 */

public class WakeLock {

    private static PowerManager.WakeLock wakeLock;

    public static void aquire(Context context) {
        release();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmManager Wakelock");
        wakeLock.acquire();
    }

    public static void aquire(Context context, int wakeLockType) {
        release();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(wakeLockType, "AlarmManager Wakelock");
        wakeLock.acquire();
    }

    public static void release() {
        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
    }
}
