package com.eveningoutpost.dexdrip.utils;

import com.eveningoutpost.dexdrip.G5Model.BatteryInfoTxMessage;
import com.eveningoutpost.dexdrip.G5Model.BluetoothServices;
import com.eveningoutpost.dexdrip.G5Model.Ob1G5StateMachine;
import com.eveningoutpost.dexdrip.G5Model.PacketShop;
import com.eveningoutpost.dexdrip.G5Model.SensorRxMessage;
import com.eveningoutpost.dexdrip.G5Model.SensorTxMessage;
import com.eveningoutpost.dexdrip.G5Model.VersionRequestTxMessage;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.exceptions.BleDisconnectedException;
import com.polidea.rxandroidble.exceptions.BleGattCharacteristicException;

import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;

import static com.eveningoutpost.dexdrip.G5Model.BluetoothServices.Control;
import static com.eveningoutpost.dexdrip.G5Model.Ob1G5StateMachine.classifyPacket;
import static com.eveningoutpost.dexdrip.G5Model.Ob1G5StateMachine.haveCurrentBatteryStatus;
import static com.eveningoutpost.dexdrip.G5Model.Ob1G5StateMachine.haveFirmwareDetails;
import static com.eveningoutpost.dexdrip.G5Model.Ob1G5StateMachine.processSensorRxMessage;
import static com.eveningoutpost.dexdrip.G5Model.Ob1G5StateMachine.setStoredBatteryBytes;
import static com.eveningoutpost.dexdrip.G5Model.Ob1G5StateMachine.setStoredFirmwareBytes;

public class G5SensorDataRequest {

    private final RxBleConnection connection;
    private Action1<PacketShop> successConsumer;
    private Action1<Throwable> failConsumer;
    private String transmitterID;

    public G5SensorDataRequest(RxBleConnection connection) {
        this.connection = connection;
        this.transmitterID = Home.getPreferencesStringWithDefault("dex_txid", "NULL");
    }

    public Subscription fire(Action1<PacketShop> successConsumer, Action1<Throwable> failConsumer) {
        this.successConsumer = successConsumer;
        this.failConsumer = failConsumer;

        if (connection == null) {
            failConsumer.call(new IllegalArgumentException("Connection must not be null."));
        }

        UserError.Log.d(getClass().getSimpleName(), "Requesting Sensor Data");

        return connection.setupIndication(Control)
                .doOnNext(this::writeSensorMessage)
                .flatMap(notificationObservable -> notificationObservable)
                .timeout(6, TimeUnit.SECONDS)
                .subscribe(this::receiveDataPacket, this::onReceivingFailure);
    }

    private void receiveDataPacket(byte[] bytes) {
        UserError.Log.d(getClass().getSimpleName(), "Received indication bytes: " + JoH.bytesToHex(bytes));
        final PacketShop dataPacket = classifyPacket(bytes);
        switch (dataPacket.getType()) {
            case SensorRxMessage:
                if (!haveFirmwareDetails()) {
                    writeVersionRequest();
                }
                if (!haveCurrentBatteryStatus()) {
                    writeBatteryInfoRequest();
                }

                this.successConsumer.call(dataPacket);
                processSensorRxMessage((SensorRxMessage) dataPacket.getMessage());
                break;

            case VersionRequestRxMessage:
                if (!setStoredFirmwareBytes(transmitterID, bytes, true)) {
                    UserError.Log.e(getClass().getSimpleName(), "Could not save out firmware version!");
                } else {
                    this.successConsumer.call(dataPacket);
                }
                break;

            case BatteryInfoRxMessage:
                if (!setStoredBatteryBytes(transmitterID, bytes)) {
                    UserError.Log.e(getClass().getSimpleName(), "Could not save out battery data!");
                } else {
                    this.successConsumer.call(dataPacket);
                }
                break;

            default:
                UserError.Log.e(getClass().getSimpleName(), "Got unknown packet instead of sensor rx: " + JoH.bytesToHex(bytes));
                this.failConsumer.call(new IllegalArgumentException("Unknown packet type: " + dataPacket));
                break;
        }
    }

    private void writeBatteryInfoRequest() {
        connection.writeCharacteristic(Control, new BatteryInfoTxMessage().byteSequence)
                .subscribe(batteryValue -> {
                    UserError.Log.d(getClass().getSimpleName(), "Wrote battery info request");
                }, throwable -> {
                    UserError.Log.e(getClass().getSimpleName(), "Failed to write BatteryInfoRequestTxMessage: " + throwable);
                });
    }

    private void writeVersionRequest() {
        connection.writeCharacteristic(Control, new VersionRequestTxMessage().byteSequence)
                .subscribe(versionValue -> {
                    UserError.Log.d(getClass().getSimpleName(), "Wrote version request");
                }, throwable -> {
                    UserError.Log.e(getClass().getSimpleName(), "Failed to write VersionRequestTxMessage: " + throwable);
                });
    }

    private void onReceivingFailure(Throwable throwable) {
        if (!(throwable instanceof Ob1G5StateMachine.OperationSuccess)) {
            if (throwable instanceof BleDisconnectedException) {
                UserError.Log.d(getClass().getSimpleName(), "Disconnected when waiting to receive indication: " + throwable);
            } else {
                UserError.Log.e(getClass().getSimpleName(), "Error receiving indication: " + throwable);
                throwable.printStackTrace();
            }
        }
    }

    private void writeSensorMessage(Observable<byte[]> observable) {
        UserError.Log.d(getClass().getSimpleName(), "Notifications enabled");
        connection.writeCharacteristic(Control, new SensorTxMessage().byteSequence).subscribe(
                characteristicValue -> {
                    UserError.Log.d(getClass().getSimpleName(), "Wrote SensorTxMessage request");
                }, throwable -> {
                    UserError.Log.e(getClass().getSimpleName(), "Failed to write SensorTxMessage: " + throwable);
                    if (throwable instanceof BleGattCharacteristicException) {
                        final int status = ((BleGattCharacteristicException) throwable).getStatus();
                        UserError.Log.e(getClass().getSimpleName(), "Got status message: " + BluetoothServices.getStatusName(status));
                        if (status == 8) {
                            this.failConsumer.call(new IllegalStateException("Request rejected due to Insufficient Authorization failure!", throwable));
                        }
                    }
                });
    }
}
