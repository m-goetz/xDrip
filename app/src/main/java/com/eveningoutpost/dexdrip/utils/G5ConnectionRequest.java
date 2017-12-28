package com.eveningoutpost.dexdrip.utils;

import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.Services.AlarmManagerG5CollectionService;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;

import java.util.concurrent.TimeUnit;

import rx.Subscription;
import rx.functions.Action1;

public class G5ConnectionRequest {

    private final RxBleClient bleClient;
    private final String macAddress;

    public G5ConnectionRequest(RxBleClient client, String address) {
        this.bleClient = client;
        this.macAddress = address;
    }

    public Subscription connect(Action1<RxBleConnection> successConsumer, Action1<Throwable> failConsumer) {
        UserError.Log.d(getClass().getSimpleName(), "Start connecting...");
        RxBleDevice bleDevice = bleClient.getBleDevice(this.macAddress);
        return bleDevice.establishConnection(true)
                .timeout(1, TimeUnit.MINUTES)
                .delay(100, TimeUnit.MILLISECONDS)
                .subscribe(successConsumer::call, failConsumer::call);
    }
}
