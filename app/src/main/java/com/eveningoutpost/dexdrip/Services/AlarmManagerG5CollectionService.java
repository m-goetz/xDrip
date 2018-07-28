package com.eveningoutpost.dexdrip.Services;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattService;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.annotation.Nullable;

import com.eveningoutpost.dexdrip.G5Model.BluetoothServices;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.utils.G5AuthorizationRequest;
import com.eveningoutpost.dexdrip.utils.G5BondingRequest;
import com.eveningoutpost.dexdrip.utils.G5ConnectionRequest;
import com.eveningoutpost.dexdrip.utils.G5DisconnectionRequest;
import com.eveningoutpost.dexdrip.utils.G5SensorDataRequest;
import com.eveningoutpost.dexdrip.utils.G5TransmitterScan;
import com.eveningoutpost.dexdrip.utils.WakeLock;
import com.eveningoutpost.dexdrip.xdrip;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.RxBleDeviceServices;
import com.polidea.rxandroidble.helpers.ValueInterpreter;
import com.polidea.rxandroidble.internal.RxBleLog;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import rx.Observable;
import rx.Subscription;

import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static com.eveningoutpost.dexdrip.G5Model.BluetoothServices.getUUIDName;

public class AlarmManagerG5CollectionService extends IntentService {

    public static final String CLASS_NAME = AlarmManagerG5CollectionService.class.getSimpleName();

    public static final long DEFAULT_TIMEFRAME_IN_MILLIS = 120000;
    public static final String DEFAULT_INTERVAL_IN_MILLIS = "20000";
    private static AlarmManagerG5CollectionService singletonService;

    private String transmitterID;
    private String macAddress;

    private long lastSuccessfulReceiverConnectionMillis;
    private Calendar plannedConnectionStartMillis;

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

    private AlarmManagerG5CollectionService.State state = AlarmManagerG5CollectionService.State.INITIAL;
    private Long timeFrameInMillis = DEFAULT_TIMEFRAME_IN_MILLIS;
    private Long intervalInMillis = Long.parseLong(Home.getPreferencesStringWithDefault("alarm_manager_collection_interval", DEFAULT_INTERVAL_IN_MILLIS));
    private long plannedReceiverRestartMillis;
    private long receiverStartMillis;

    private Observable<byte[]> notificationObservable;
    private Subscription notificationSubscription;

    //private int errorCount = 3;

    private enum State {
        INITIAL,
        AWAIT_BOND,
        DATA_COLLECT
    }

    public AlarmManagerG5CollectionService() {
        super("AlarmManagerG5CollectionService");
    }

    public static void reset() {
        singletonService.unsubscribeAllRequests();
        singletonService.cancelAlarm();
        singletonService = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        UserError.Log.wtf(CLASS_NAME,"AlarmManagerCollectionService destroyed.");
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        this.receiverStartMillis = System.currentTimeMillis();
        UserError.Log.i(CLASS_NAME, "Started" + CLASS_NAME + " at " + format(Calendar.getInstance()));

        WakeLock.aquire(this);

        if (singletonService != null) {
            copyObjectState(singletonService);
        }
        singletonService = this;

        if (plannedConnectionStartMillis != null) {
            UserError.Log.i(CLASS_NAME,"Planned Connection at " + format(plannedConnectionStartMillis));
        }

        boolean startedTooLate = plannedReceiverRestartMillis != 0L && (receiverStartMillis - plannedReceiverRestartMillis) > 20000;
        if (this.state == AlarmManagerG5CollectionService.State.DATA_COLLECT && startedTooLate) {
            UserError.Log.wtf(CLASS_NAME, "Started too late.");
        }

        enableBluetooth();

        UserError.Log.d(CLASS_NAME, "Current State: " + this.state);

        switch (this.state) {
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
                    restartService(nextCollectingTry());
                    break;
                }

                if (weAreOneMinuteBeforeConnectionTimeframe() && startedTooLate) {
                    long sleepTime = (plannedConnectionStartMillis.getTimeInMillis() - timeFrameInMillis) - System.currentTimeMillis();
                    sleepTime += 10000;
                    UserError.Log.wtf(CLASS_NAME, "One minute before Timeframe, sleeping for " + sleepTime + " ms.");
                    SystemClock.sleep(sleepTime);
                    this.timeFrameInMillis += 10000;
                    collectData();
                    break;
                }

                if (weAreInTimeframeBeforeConnection()) {
                    collectDataWithScanning();
                    break;
                } else {
                    UserError.Log.i(CLASS_NAME, "Not in Timeframe. Restarting...");
                    restartService(nextCollectingTry());
                    break;
                }

        }
        UserError.Log.i(CLASS_NAME, "Finished onReceive.");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        UserError.Log.wtf(CLASS_NAME, "AlarmManagerG5CollectionService started.");
    }

    public void copyObjectState(AlarmManagerG5CollectionService serviceToCopy) {
        this.transmitterID = serviceToCopy.transmitterID;
        this.macAddress = serviceToCopy.macAddress;
        this.lastSuccessfulReceiverConnectionMillis = serviceToCopy.lastSuccessfulReceiverConnectionMillis;
        this.rxBleClient = serviceToCopy.rxBleClient;
        this.bleDevice = serviceToCopy.bleDevice;
        this.scanSubscription = serviceToCopy.scanSubscription;
        this.connectionSubscription = serviceToCopy.connectionSubscription;
        this.connection = serviceToCopy.connection;
        this.state = serviceToCopy.state;
        this.plannedConnectionStartMillis = serviceToCopy.plannedConnectionStartMillis;
        this.timeFrameInMillis = serviceToCopy.timeFrameInMillis;
        this.intervalInMillis = serviceToCopy.intervalInMillis;
        this.plannedReceiverRestartMillis = serviceToCopy.plannedReceiverRestartMillis;
        this.notificationObservable = serviceToCopy.notificationObservable;
        this.notificationSubscription = serviceToCopy.notificationSubscription;
    }

    private boolean weAreInTimeframeBeforeConnection() {
        return System.currentTimeMillis() < plannedConnectionStartMillis.getTimeInMillis()
                && System.currentTimeMillis() > plannedConnectionStartMillis.getTimeInMillis() - timeFrameInMillis;
    }

    private boolean weAreOneMinuteBeforeConnectionTimeframe() {
        return System.currentTimeMillis() < plannedConnectionStartMillis.getTimeInMillis() - timeFrameInMillis
                && System.currentTimeMillis() > plannedConnectionStartMillis.getTimeInMillis() - (timeFrameInMillis + 60000) ;
    }

    private void enableBluetooth() {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter != null && !mBluetoothAdapter.isEnabled()) {
            mBluetoothAdapter.enable();
        }
    }

    private void collectDataWithScanning() {
        String dexId = Home.getPreferencesStringWithDefault("dex_txid", "000000");
        dexId = "Dexcom" + dexId.substring(4);
        this.transmitterScan = new G5TransmitterScan(rxBleClient, dexId).doScan(this, scanResult -> {
            UserError.Log.d(CLASS_NAME, "Device found" +
                    ": " + scanResult.getBleDevice().getName());
            this.bleDevice = scanResult.getBleDevice();
            this.macAddress = bleDevice.getMacAddress();
            this.lastSuccessfulReceiverConnectionMillis = System.currentTimeMillis();
            if (deviceIsBonded()) {
                this.state = State.DATA_COLLECT;
                this.connectionRequest = new G5ConnectionRequest(this.rxBleClient, this.macAddress, 10000, false).connect(this, rxBleConnection -> {
                    this.connection = rxBleConnection;
                    this.lastSuccessfulReceiverConnectionMillis = System.currentTimeMillis();
                    this.timeFrameInMillis = DEFAULT_TIMEFRAME_IN_MILLIS;
                    this.intervalInMillis = Long.parseLong(Home.getPreferencesStringWithDefault(
                            "alarm_manager_collection_interval", DEFAULT_INTERVAL_IN_MILLIS));
                    UserError.Log.d(CLASS_NAME, "Connected to device. Now authorizing" +
                            ": " + rxBleConnection.toString());
                    setupNotification(connection);
                    authorizeAndGetData();
                }, this::printStacktraceAndTryAgain);
            } else {
                establishConnectionForServiceDiscovery();
            }
        }, this::printStacktraceAndTryAgain);
    }

    private void collectData() {
        this.connectionRequest = new G5ConnectionRequest(this.rxBleClient, this.macAddress, this.timeFrameInMillis + 10000).connect(rxBleConnection -> {
            this.connection = rxBleConnection;
            this.lastSuccessfulReceiverConnectionMillis = System.currentTimeMillis();
            this.timeFrameInMillis = DEFAULT_TIMEFRAME_IN_MILLIS;
            this.intervalInMillis = Long.parseLong(Home.getPreferencesStringWithDefault(
                    "alarm_manager_collection_interval", DEFAULT_INTERVAL_IN_MILLIS));;
            //this.errorCount = 3;
            setupNotification(connection);
            authorizeAndGetData();
        }, this::printStacktraceAndTryAgain);
    }

    private void setupNotification(RxBleConnection rxBleConnection) {
        if (this.notificationSubscription == null) {
            this.notificationSubscription = rxBleConnection.setupNotification(BluetoothServices.Communication)
                    .doOnNext(notificationObservable -> {
                        this.notificationObservable = notificationObservable;
                        UserError.Log.wtf(CLASS_NAME, "Got Notification Observable");
                    })
                    .flatMap(notificationObservable -> notificationObservable)
                    .subscribe(
                            bytes -> {
                                UserError.Log.wtf(CLASS_NAME, "NOTIFICATION: " + Arrays.toString(bytes));
                            },
                            throwable -> {
                                UserError.Log.wtf(CLASS_NAME, "NOTIFICATION throwable: " + throwable);
                            }
                    );
        }
        else {
            UserError.Log.wtf(CLASS_NAME, "Not recreating Notification Observable");
        }
    }

    private void authorizeAndGetData() {
        this.authorizationRequest = new G5AuthorizationRequest(connection).fire(bytes ->
                new G5SensorDataRequest(this.connection).fire(packetShop -> {
                    UserError.Log.wtf(CLASS_NAME, "Got Data from SensorDataRequest.");
                    disconnectAndRestart();
                }, throwable -> UserError.Log.d(CLASS_NAME, "SensorDataRequest failed.")), this::printStacktraceAndTryAgain);
    }

    private void startScanning() {
        String dexId = Home.getPreferencesStringWithDefault("dex_txid", "000000");
        dexId = "Dexcom" + dexId.substring(4);
        this.transmitterScan = new G5TransmitterScan(rxBleClient, dexId).doScan(scanResult -> {
            UserError.Log.d(CLASS_NAME, "Device found" +
                    ": " + scanResult.getBleDevice().getName());
            this.bleDevice = scanResult.getBleDevice();
            bleDevice.observeConnectionStateChanges().subscribe(
                    connectionState -> {
                        UserError.Log.wtf(CLASS_NAME, "Connection state: " + connectionState);
                    },
                    throwable -> {
                        UserError.Log.wtf(CLASS_NAME, "Connection state fail: " + throwable.getMessage());
                    }
            );
            this.macAddress = bleDevice.getMacAddress();
            this.lastSuccessfulReceiverConnectionMillis = System.currentTimeMillis();
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
            UserError.Log.d(CLASS_NAME, "Transmitter ID is: " + transmitterID);
        }
    }

    private void establishConnectionForServiceDiscovery() {
        UserError.Log.d(CLASS_NAME, "Scan Completed.");
        UserError.Log.d(CLASS_NAME, "Dexcom found: " + this.macAddress);
        this.connectionRequest = new G5ConnectionRequest(this.rxBleClient, this.macAddress).connect(rxBleConnection -> {
            this.connection = rxBleConnection;
            this.lastSuccessfulReceiverConnectionMillis = System.currentTimeMillis();
            UserError.Log.d(CLASS_NAME, "Established... " + connection.toString());
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
            UserError.Log.d(CLASS_NAME, "Authorization successful!");
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
        UserError.Log.d(CLASS_NAME, "Bonding successful! Await user response to complete bonding.");
        if (deviceIsBonded()) {
            UserError.Log.d(CLASS_NAME, "User confirmed bonding.");
            this.state = AlarmManagerG5CollectionService.State.DATA_COLLECT;
            disconnectAndRestart();
        } else {
            UserError.Log.d(CLASS_NAME, "User did not confirm bonding. Try again.");
            this.state = AlarmManagerG5CollectionService.State.AWAIT_BOND;
            restartService();
        }
    }

    private void disconnectAndRestart() {
        this.disconnectionRequest = new G5DisconnectionRequest(connection).disconnect(disconnectBytes ->
                restartService(nextCollectingTry()), throwable -> {
            UserError.Log.d(CLASS_NAME, "Disconnect caused exception: " + throwable);
            restartService(nextCollectingTry());
        });
    }

    private boolean deviceIsBonded() {
        return this.bleDevice.getBluetoothDevice().getBondState() == BluetoothDevice.BOND_BONDED;
    }

    @TargetApi(LOLLIPOP)
    private void createBond() {
        this.bleDevice.getBluetoothDevice().createBond();
    }

    private void logG5Services(RxBleDeviceServices rxBleDeviceServices) {
        for (BluetoothGattService service : rxBleDeviceServices.getBluetoothGattServices()) {
            UserError.Log.d(CLASS_NAME, "Service: " + getUUIDName(service.getUuid()));
            if (service.getUuid().equals(BluetoothServices.CGMService)) {
                UserError.Log.i(CLASS_NAME, "Found CGM Service!");
            }
        }
    }

    private void printStacktraceAndTryAgain(Throwable throwable) {
        UserError.Log.wtf(throwable.getClass().getSimpleName(), (Exception) throwable);
        restartService(nextCollectingTry());
    }

    public void restartService() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis() + 500L);
        restartService(calendar);
    }

    @TargetApi(23)
    public void restartService(Calendar cal) {
        AlarmManager alarmMgr = (AlarmManager) this.getSystemService(ALARM_SERVICE);
        Intent receiverIntent = new Intent(this, AlarmManagerG5CollectionService.class);
        PendingIntent alarmIntent = PendingIntent.getService(this, 0, receiverIntent, 0);

        cal.setTimeInMillis(cal.getTimeInMillis());

        if (alarmMgr != null)
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), alarmIntent);

        unsubscribeAllRequests();

        UserError.Log.i(CLASS_NAME,"Next Service start planned for: " + format(cal));
        WakeLock.release();
    }

    private void cancelAlarm() {
        AlarmManager alarmMgr = (AlarmManager) this.getSystemService(ALARM_SERVICE);
        Intent receiverIntent = new Intent(this, AlarmManagerG5CollectionService.class);
        PendingIntent alarmIntent = PendingIntent.getService(this, 0, receiverIntent, 0);
        alarmMgr.cancel(alarmIntent);
    }

    private void unsubscribeAllRequests() {
        if (this.scanSubscription != null) this.scanSubscription.unsubscribe();
        if (this.connectionSubscription != null) this.connectionSubscription.unsubscribe();
        if (this.connectionRequest != null) this.connectionRequest.unsubscribe();
        if (this.disconnectionRequest != null) this.disconnectionRequest.unsubscribe();
        if (this.bondingRequest != null) this.bondingRequest.unsubscribe();
        if (this.transmitterScan != null) this.transmitterScan.unsubscribe();
        if (this.authorizationRequest != null) this.authorizationRequest.unsubscribe();
    }

    private Calendar nextCollectingTry() {
        int offsetInMinutes;
        long calendarBaseTimeMillis;

        if (lastSuccessfulReceiverConnectionMillis == 0) {
            calendarBaseTimeMillis = System.currentTimeMillis();
            offsetInMinutes = 1;
        } else {
            calendarBaseTimeMillis = lastSuccessfulReceiverConnectionMillis;
            offsetInMinutes = 5;

            while (plannedStartIsInPast(offsetInMinutes)) {
                offsetInMinutes += 5;
            }
        }

        Calendar plannedStart = Calendar.getInstance();
        plannedStart.setTimeInMillis(calendarBaseTimeMillis);
        plannedStart.set(Calendar.MINUTE, plannedStart.get(Calendar.MINUTE) + offsetInMinutes);
        this.plannedConnectionStartMillis = plannedStart;

        Calendar nextTry = Calendar.getInstance();
        nextTry.setTimeInMillis(this.receiverStartMillis + intervalInMillis);
        this.plannedReceiverRestartMillis = nextTry.getTimeInMillis();

        return nextTry;
    }

    private boolean plannedStartIsInPast(int offsetInMinutes) {
        return (lastSuccessfulReceiverConnectionMillis + (offsetInMinutes * 60 * 1000)) < System.currentTimeMillis();
    }

    public static String format(Calendar calendar) {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss:SSS");
        fmt.setCalendar(calendar);
        return fmt.format(calendar.getTime());
    }

}
