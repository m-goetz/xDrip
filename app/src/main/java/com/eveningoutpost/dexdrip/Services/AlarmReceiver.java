package com.eveningoutpost.dexdrip.Services;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.eveningoutpost.dexdrip.G5Model.BluetoothServices;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.utils.G5AuthorizationRequest;
import com.eveningoutpost.dexdrip.utils.G5BondingRequest;
import com.eveningoutpost.dexdrip.utils.G5ConnectionRequest;
import com.eveningoutpost.dexdrip.utils.G5DisconnectionRequest;
import com.eveningoutpost.dexdrip.utils.G5SensorDataRequest;
import com.eveningoutpost.dexdrip.utils.G5TransmitterScan;
import com.eveningoutpost.dexdrip.xdrip;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.RxBleDeviceServices;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import rx.Subscription;

import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static com.eveningoutpost.dexdrip.G5Model.BluetoothServices.getUUIDName;

public class AlarmReceiver extends BroadcastReceiver {

    private static AlarmReceiver singletonAlarmReceiver;

    private Context context;

    private String transmitterID;
    private String macAddress;

    private long lastSuccessfulReceiverStartMillis;
    private long receiverStartMillis;

    private RxBleClient rxBleClient;
    private RxBleDevice bleDevice;
    private RxBleConnection connection;

    private Subscription scanSubscription;
    private Subscription connectionSubscription;
    private Subscription bondingRequest;
    private Subscription authorizationRequest;
    private Subscription connectionRequest;
    private Subscription transmitterScan;
    private Subscription disconnectionRequest;

    private State state = State.INITIAL;

    private enum State {
        INITIAL,
        AWAIT_BOND,
        DATA_COLLECT
    }

    public void copyObjectState(AlarmReceiver receiverToCopy) {
        this.transmitterID = receiverToCopy.transmitterID;
        this.macAddress = receiverToCopy.macAddress;
        this.lastSuccessfulReceiverStartMillis = receiverToCopy.lastSuccessfulReceiverStartMillis;
        this.rxBleClient = receiverToCopy.rxBleClient;
        this.bleDevice = receiverToCopy.bleDevice;
        this.scanSubscription = receiverToCopy.scanSubscription;
        this.connectionSubscription = receiverToCopy.connectionSubscription;
        this.connection = receiverToCopy.connection;
        this.state = receiverToCopy.state;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;
        this.receiverStartMillis = System.currentTimeMillis();

        if (singletonAlarmReceiver != null) {
            copyObjectState(singletonAlarmReceiver);
            UserError.Log.i(AlarmManagerG5CollectionService.CLASS_NAME, "Restarted AlarmReceiver");
        }
        singletonAlarmReceiver = this;

        switch(this.state) {
            case INITIAL:
                createRxBleClientIfNull();
                getTransmitterIdIfNotExists();
                startScanning();
                break;
            case AWAIT_BOND:
                checkForSuccessfulBonding();
                break;
            case DATA_COLLECT:
                if (this.bleDevice.getConnectionState() != RxBleConnection.RxBleConnectionState.DISCONNECTED) {
                    restartAlarmReceiver(100);
                } else {
                    collectData();
                }
                break;
        }
    }

    private void collectData() {
        this.connectionRequest = new G5ConnectionRequest(this.rxBleClient, this.macAddress).connect(rxBleConnection -> {
            this.connection = rxBleConnection;
            //this.lastSuccessfulReceiverStartMillis = receiverStartMillis;
            this.lastSuccessfulReceiverStartMillis = System.currentTimeMillis();
            authorizeAndGetData();
        }, this::printStacktraceAndTryAgain);
    }

    private void authorizeAndGetData() {
        this.authorizationRequest = new G5AuthorizationRequest(connection).fire(bytes -> {
            new G5SensorDataRequest(this.connection).fire(packetShop -> {
                UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "Got Data from SensorDataRequest.");
                disconnectAndRestart();
            }, throwable -> {
                UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "SensorDataRequest failed.");
            });
        }, this::printStacktraceAndTryAgain);
    }

    private void startScanning() {
        this.transmitterScan = new G5TransmitterScan(rxBleClient, "DexcomC8").doScan(scanResult -> {
            UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "Device found: " + scanResult.getBleDevice().getName());
            this.bleDevice = scanResult.getBleDevice();
            this.macAddress = bleDevice.getMacAddress();
            if (deviceIsBonded()) {
                this.state = State.DATA_COLLECT;
                collectData();
            } else {
                establishConnectionForServiceDiscovery();
            }
        }, this::printStacktraceAndTryAgain);
    }

    private void createRxBleClientIfNull() {
        if (rxBleClient == null) {
            rxBleClient = RxBleClient.create(xdrip.getAppContext());
        }
    }

    private void getTransmitterIdIfNotExists() {
        if (transmitterID == null) {
            transmitterID = Home.getPreferencesStringWithDefault("dex_txid", "NULL");
            UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "Transmitter ID is: " + transmitterID);
        }
    }

    private void establishConnectionForServiceDiscovery() {
        UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "Scan Completed.");
        UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "Dexcom found: " + this.macAddress);
        this.connectionRequest = new G5ConnectionRequest(this.rxBleClient, this.macAddress).connect(rxBleConnection -> {
            this.connection = rxBleConnection;
            //this.lastSuccessfulReceiverStartMillis = receiverStartMillis;
            this.lastSuccessfulReceiverStartMillis = System.currentTimeMillis();
            UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "Established... " + connection.toString());
            discoverServices();
        }, this::printStacktraceAndTryAgain);
    }

    private void discoverServices() {
        connection.discoverServices(10, TimeUnit.SECONDS).subscribe(rxBleDeviceServices -> {
            logG5Services(rxBleDeviceServices);
            fireAuthorizationRequest();
        }, this::printStacktraceAndTryAgain);
    }

    private void fireAuthorizationRequest() {
        this.authorizationRequest = new G5AuthorizationRequest(connection).fire(bytes -> {
            UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "Authorization successful!");
            fireBondingRequest();
        }, this::printStacktraceAndTryAgain);
    }

    private void fireBondingRequest() {
        this.bondingRequest = new G5BondingRequest(connection).fire(bytes -> {
            createBond();
            checkForSuccessfulBonding();
        }, this::printStacktraceAndTryAgain);
    }

    private void checkForSuccessfulBonding() {
        UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "Bonding successful! Await user response to complete bonding.");
        if (deviceIsBonded()) {
            UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "User confirmed bonding.");
            this.state = State.DATA_COLLECT;
            disconnectAndRestart();
        } else {
            UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "User did not confirm bonding. Try again.");
            this.state = State.AWAIT_BOND;
            restartAlarmReceiver(500L);
        }
    }

    private void disconnectAndRestart() {
        this.disconnectionRequest = new G5DisconnectionRequest(connection).disconnect(disconnectBytes -> {
            restartAlarmReceiver(nextCollectingTime());
        }, throwable -> {
            UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "Disconnect caused exception: " + throwable);
            restartAlarmReceiver(nextCollectingTime());
        });
    }

    private boolean deviceIsBonded() {
        return this.bleDevice.getBluetoothDevice().getBondState() == BluetoothDevice.BOND_BONDED;
    }

    @TargetApi(LOLLIPOP)
    private void createBond() {
        this.bleDevice.getBluetoothDevice().createBond();
    }

    private void onConnectionStateChange(RxBleConnection.RxBleConnectionState connectionState) {
        UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "New Connection State: " + connectionState);
    }

    private void logG5Services(RxBleDeviceServices rxBleDeviceServices) {
        for (BluetoothGattService service : rxBleDeviceServices.getBluetoothGattServices()) {
            UserError.Log.d(AlarmManagerG5CollectionService.CLASS_NAME, "Service: " + getUUIDName(service.getUuid()));
            if (service.getUuid().equals(BluetoothServices.CGMService)) {
                UserError.Log.i(AlarmManagerG5CollectionService.CLASS_NAME, "Found CGM Service!");
            }
        }
    }

    private void printStacktraceAndTryAgain(Throwable throwable) {
        throwable.printStackTrace();
        restartAlarmReceiver(nextCollectingTime());
    }

    public void restartAlarmReceiver(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis() + millis);
        restartAlarmReceiver(calendar);
    }

    @TargetApi(23)
    public void restartAlarmReceiver(Calendar cal) {
        AlarmManager alarmMgr = (AlarmManager) context.getSystemService(context.ALARM_SERVICE);
        Intent receiverIntent = new Intent(context, AlarmReceiver.class);
        PendingIntent alarmIntent = PendingIntent.getBroadcast(context.getApplicationContext(), 0, receiverIntent, 0);
        alarmMgr.setWindow(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis() - 5000, 4000, alarmIntent);

        if (this.connectionRequest != null) this.connectionRequest.unsubscribe();
        if (this.disconnectionRequest != null) this.disconnectionRequest.unsubscribe();
        if (this.bondingRequest != null) this.bondingRequest.unsubscribe();
        if (this.transmitterScan != null) this.transmitterScan.unsubscribe();
        if (this.authorizationRequest != null) this.authorizationRequest.unsubscribe();

        UserError.Log.i(AlarmManagerG5CollectionService.CLASS_NAME,
                "Next AlarmReceiver start planned for: " + format(cal));
    }

    private Calendar nextCollectingTime() {
        int offsetInMinutes;
        long calendarBaseTimeMillis;

        if (lastSuccessfulReceiverStartMillis == 0) {
            calendarBaseTimeMillis = System.currentTimeMillis();
            offsetInMinutes = 1;
        } else {
            calendarBaseTimeMillis = lastSuccessfulReceiverStartMillis;
            offsetInMinutes = 5;

            while (plannedStartIsInPast(offsetInMinutes)) {
                offsetInMinutes += 5;
            }
        }

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(calendarBaseTimeMillis);
        calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE) + offsetInMinutes);

        return calendar;
    }

    private boolean plannedStartIsInPast(int offsetInMinutes) {
        return (lastSuccessfulReceiverStartMillis + (offsetInMinutes * 60 * 1000)) < System.currentTimeMillis();
    }

    public static String format(Calendar calendar){
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss:SSS");
        fmt.setCalendar(calendar);
        String dateFormatted = fmt.format(calendar.getTime());
        return dateFormatted;
    }
}
