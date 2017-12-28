package com.eveningoutpost.dexdrip.utils;

import com.eveningoutpost.dexdrip.G5Model.DisconnectTxMessage;
import com.eveningoutpost.dexdrip.G5Model.Ob1G5StateMachine;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.Services.Ob1G5CollectionService;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.exceptions.BleDisconnectedException;

import java.util.concurrent.TimeUnit;

import rx.Subscription;
import rx.functions.Action1;

import static com.eveningoutpost.dexdrip.G5Model.BluetoothServices.Control;

public class G5DisconnectionRequest {

    private final RxBleConnection connection;

    public G5DisconnectionRequest(RxBleConnection connection) {
        this.connection = connection;
    }

    public Subscription disconnect(Action1<byte[]> successConsumer, Action1<Throwable> failConsumer) {
        UserError.Log.d(getClass().getSimpleName(), "Disconnect NOW: " + JoH.dateTimeText(JoH.tsl()));
        return connection.writeCharacteristic(Control, new DisconnectTxMessage().byteSequence)
                .timeout(2, TimeUnit.SECONDS)
                .subscribe(successConsumer::call, failConsumer::call);
    }
}
