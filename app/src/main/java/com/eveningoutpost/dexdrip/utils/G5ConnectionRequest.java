package com.eveningoutpost.dexdrip.utils;

import android.app.Application;
import android.content.Context;

import com.eveningoutpost.dexdrip.Models.UserError;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;

import java.util.concurrent.TimeUnit;

import rx.Subscription;
import rx.functions.Action1;

public class G5ConnectionRequest {

    private final RxBleClient bleClient;
    private final String macAddress;

    private final long timeoutInMillis;
    private boolean autoconnect;

    public G5ConnectionRequest(RxBleClient client, String address) {
        this(client, address, 60000);
    }

    public G5ConnectionRequest(RxBleClient client, String address, long timeoutInMillis) {
        this(client, address, timeoutInMillis, true );
    }

    public G5ConnectionRequest(RxBleClient client, String address, long timeoutInMillis, boolean autoconnect) {
        this.bleClient = client;
        this.macAddress = address;
        this.timeoutInMillis = timeoutInMillis;
        this.autoconnect = autoconnect;
    }

    public Subscription connect(Action1<RxBleConnection> successConsumer, Action1<Throwable> failConsumer) {
        UserError.Log.d(getClass().getSimpleName(), "Start connecting...");
        RxBleDevice bleDevice = bleClient.getBleDevice(this.macAddress);
        return bleDevice.establishConnection(this.autoconnect)
                .timeout(this.timeoutInMillis, TimeUnit.MILLISECONDS)
                .subscribe(successConsumer::call, failConsumer::call);
    }

    public Subscription connect(Context context, Action1<RxBleConnection> successConsumer, Action1<Throwable> failConsumer) {
        UserError.Log.d(getClass().getSimpleName(), "Start connecting...");
        RxBleDevice bleDevice = bleClient.getBleDevice(this.macAddress);
        return bleDevice.establishConnection(this.autoconnect)
                .doOnSubscribe(() -> WakeLock.aquire(context))
                .doOnTerminate(() -> WakeLock.release())
                .retry(3)
                .timeout(this.timeoutInMillis, TimeUnit.MILLISECONDS)
                .subscribe(successConsumer::call, failConsumer::call);
    }
}
