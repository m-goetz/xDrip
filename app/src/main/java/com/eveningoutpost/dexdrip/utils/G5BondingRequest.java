package com.eveningoutpost.dexdrip.utils;

import android.bluetooth.BluetoothGatt;
import android.os.Build;

import com.eveningoutpost.dexdrip.G5Model.BondRequestTxMessage;
import com.eveningoutpost.dexdrip.G5Model.KeepAliveTxMessage;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.polidea.rxandroidble.RxBleConnection;

import java.util.concurrent.TimeUnit;
import rx.Subscription;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

import static com.eveningoutpost.dexdrip.G5Model.BluetoothServices.Authentication;

public class G5BondingRequest {

    private final RxBleConnection connection;
    private Action1<byte[]> successConsumer;
    private Action1<Throwable> failConsumer;

    public G5BondingRequest(RxBleConnection connection) {
        this.connection = connection;
    }

    public Subscription fire(Action1<byte[]> successConsumer, Action1<Throwable> failConsumer) {
        this.successConsumer = successConsumer;
        this.failConsumer = failConsumer;

        if (connection == null) {
            failConsumer.call(new IllegalArgumentException("Connection must not be null."));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            UserError.Log.d(getClass().getSimpleName(), "Requesting high priority");
            connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH, 500, TimeUnit.MILLISECONDS);
        }
        UserError.Log.e(getClass().getSimpleName(), "Sending keepalive..");
        return connection.writeCharacteristic(Authentication, new KeepAliveTxMessage(25).byteSequence)
                .subscribe(this::writeBondRequest, throwable -> {
                    // Could not write keep alive ? retry?
                    UserError.Log.e(getClass().getSimpleName(), "Failed writing keep-alive request! " + throwable);
                    this.failConsumer.call(throwable);
                });
    }

    private void writeBondRequest(byte[] bytes) {
        UserError.Log.d(getClass().getSimpleName(), "Wrote keep-alive request successfully");
        //parent.unBond();
        //parent.instantCreateBond();
        connection.writeCharacteristic(Authentication, new BondRequestTxMessage().byteSequence)
                .subscribe(this::readBondResponse, throwable -> {
                    // failed to write bond request retry?
                    UserError.Log.e(getClass().getSimpleName(), "Failed to write bond request! " + throwable);
                    this.failConsumer.call(throwable);
        });
    }

    private void readBondResponse(byte[] bondRequestValue) {
        UserError.Log.d(getClass().getSimpleName(), "Wrote bond request value: " + JoH.bytesToHex(bondRequestValue));
        connection.readCharacteristic(Authentication)
                .observeOn(Schedulers.io())
                .timeout(10, TimeUnit.SECONDS)
                .subscribe(this::markBondingSuccessful, throwable -> {
                    UserError.Log.e(getClass().getSimpleName(), "Throwable when reading characteristic after keepalive: " + throwable);
                    this.failConsumer.call(throwable);
                });
    }

    private void markBondingSuccessful(byte[] status_value) {
        UserError.Log.d(getClass().getSimpleName(), "Got status read after keepalive " + JoH.bytesToHex(status_value));

        UserError.Log.d(getClass().getSimpleName(), "Wrote bond request successfully");
        this.successConsumer.call(status_value);
    }
}
