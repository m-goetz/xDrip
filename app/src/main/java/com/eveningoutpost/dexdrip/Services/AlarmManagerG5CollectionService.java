package com.eveningoutpost.dexdrip.Services;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;

import com.eveningoutpost.dexdrip.Models.UserError;

public class AlarmManagerG5CollectionService extends G5BaseService {

    public static final String CLASS_NAME = AlarmManagerG5CollectionService.class.getSimpleName();

    private AlarmManager alarmMgr;
    private PendingIntent alarmIntent;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        UserError.Log.d(CLASS_NAME, "Started AlarmManagerG5Collector!");

        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {

    }

    @Override
    public void onCreate() {
        super.onCreate();

        alarmMgr = (AlarmManager) this.getSystemService(this.ALARM_SERVICE);
        Intent receiverIntent = new Intent(this, AlarmReceiver.class);
        alarmIntent = PendingIntent.getBroadcast(this.getApplicationContext(), 0, receiverIntent, 0);
        alarmMgr.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, alarmIntent);

        UserError.Log.d(CLASS_NAME, "AlarmManager Repeating set!");
        UserError.Log.d(CLASS_NAME, "Created AlarmManagerG5Collector!");
    }

}
