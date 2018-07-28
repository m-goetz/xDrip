package com.eveningoutpost.dexdrip.utils;

import android.content.Context;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.scan.ScanResult;
import com.polidea.rxandroidble.scan.ScanSettings;

import java.util.concurrent.TimeUnit;

import rx.Subscription;
import rx.functions.Action1;

public class G5TransmitterScan {

    private final RxBleClient rxBleClient;
    private final String deviceName;
    private static final int SCANNING_TIME_MINUTES = 7;

    private Action1<ScanResult> successConsumer;
    private Action1<Throwable> failConsumer;

    public G5TransmitterScan(RxBleClient rxBleClient, String deviceName) {
        this.rxBleClient = rxBleClient;
        this.deviceName = deviceName;
    }
    
    public Subscription doScan(Action1<ScanResult> successConsumer, Action1<Throwable> failConsumer) {
        this.successConsumer = successConsumer;
        this.failConsumer = failConsumer;

        return rxBleClient.scanBleDevices(
                new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .build())
                .take(SCANNING_TIME_MINUTES, TimeUnit.MINUTES)
                .takeFirst(this::withDexcomDeviceName)
                .subscribe(this.successConsumer::call, this.failConsumer::call);
    }

    public Subscription doScan(Context context, Action1<ScanResult> successConsumer, Action1<Throwable> failConsumer) {
        this.successConsumer = successConsumer;
        this.failConsumer = failConsumer;

        return rxBleClient.scanBleDevices(
                new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .build())
                .take(SCANNING_TIME_MINUTES, TimeUnit.MINUTES)
                .doOnSubscribe(() -> WakeLock.aquire(context))
                .doOnTerminate(() -> WakeLock.release())
                .takeFirst(this::withDexcomDeviceName)
                .subscribe(this.successConsumer::call, this.failConsumer::call);
    }

    private Boolean withDexcomDeviceName(ScanResult scanResult) {
        return this.deviceName.equals(scanResult.getBleDevice().getName());
    }
}
