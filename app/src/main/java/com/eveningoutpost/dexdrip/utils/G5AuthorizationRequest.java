package com.eveningoutpost.dexdrip.utils;

import com.eveningoutpost.dexdrip.G5Model.AuthChallengeRxMessage;
import com.eveningoutpost.dexdrip.G5Model.AuthChallengeTxMessage;
import com.eveningoutpost.dexdrip.G5Model.AuthRequestTxMessage;
import com.eveningoutpost.dexdrip.G5Model.AuthStatusRxMessage;
import com.eveningoutpost.dexdrip.G5Model.Packet;
import com.eveningoutpost.dexdrip.G5Model.PacketShop;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.polidea.rxandroidble.RxBleConnection;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;

import static com.eveningoutpost.dexdrip.G5Model.BluetoothServices.Authentication;
import static com.eveningoutpost.dexdrip.G5Model.Ob1G5StateMachine.calculateHash;
import static com.eveningoutpost.dexdrip.G5Model.Ob1G5StateMachine.classifyPacket;

public class G5AuthorizationRequest {

    private static final int TOKEN_SIZE = 8;

    private AuthRequestTxMessage authRequest;
    private final RxBleConnection connection;

    private Action1<byte[]> successConsumer;
    private Action1<Throwable> failConsumer;

    public G5AuthorizationRequest(RxBleConnection connection) {
        this.authRequest = new AuthRequestTxMessage(TOKEN_SIZE);
        this.connection = connection;
    }

    public Subscription fire(Action1<byte[]> successConsumer, Action1<Throwable> failConsumer) {
        this.successConsumer = successConsumer;
        this.failConsumer = failConsumer;

        if (connection == null) {
            this.failConsumer.call(new IllegalArgumentException("Connection must not be null."));
        }

        UserError.Log.i(getClass().getSimpleName(), "AuthRequestTX: " + JoH.bytesToHex(authRequest.byteSequence));

        return connection.setupNotification(Authentication)
                .timeout(15, TimeUnit.SECONDS) // WARN
                .doOnNext(this::writeAuthRequest)
                .flatMap(notificationObservable -> notificationObservable)
                .subscribe(bytes -> {
                    UserError.Log.e(getClass().getSimpleName(), "Received Authentication notification bytes: " + JoH.bytesToHex(bytes));
                }, this.failConsumer::call);
    }

    private void writeAuthRequest(Observable<byte[]> observable) {
        connection.writeCharacteristic(Authentication, authRequest.byteSequence)
                .subscribe(this::lookForAuthWriteConfirmation, this.failConsumer::call);
    }

    private void lookForAuthWriteConfirmation(byte[] characteristicValue) {
        UserError.Log.d(getClass().getSimpleName(), "Wrote authrequest, got: " + JoH.bytesToHex(characteristicValue));
        connection.readCharacteristic(Authentication).subscribe(this::writeAuthChallenge, this.failConsumer::call);
    }

    private void writeAuthChallenge(byte[] readValue) {
        PacketShop packet = classifyPacket(readValue);
        UserError.Log.d(getClass().getSimpleName(), "Read from auth request: " + packet.getType() + " " + JoH.bytesToHex(readValue));

        if (packet.getType() == Packet.AuthChallengeRxMessage) {
            // Respond to the challenge request
            byte[] challengeHash = calculateHash(((AuthChallengeRxMessage) packet.getMessage()).challenge);
            UserError.Log.d(getClass().getSimpleName(), "challenge hash" + Arrays.toString(challengeHash));
            if (challengeHash != null) {
                UserError.Log.d(getClass().getSimpleName(), "Transmitter trying auth challenge");
                connection.writeCharacteristic(Authentication, new AuthChallengeTxMessage(challengeHash).byteSequence)
                        .subscribe(this::lookForAuthChallengeConfirmation, this.failConsumer::call);
            } else {
                UserError.Log.e(getClass().getSimpleName(), "Could not generate challenge hash! - resetting");
                return;
            }
        } else {
            UserError.Log.e(getClass().getSimpleName(), "Unhandled packet type in reply: " + packet.getType() + " " + JoH.bytesToHex(readValue));
        }
    }

    private void lookForAuthChallengeConfirmation(byte[] bytes) {
        connection.readCharacteristic(Authentication).subscribe(this::markOperationAsSuccess, this.failConsumer::call);
    }

    private void markOperationAsSuccess(byte[] statusValue) {
        final PacketShop statusPacket = classifyPacket(statusValue);
        UserError.Log.d(getClass().getSimpleName(), statusPacket.getType() + " " + JoH.bytesToHex(statusValue));
        if (statusPacket.getType() == Packet.AuthStatusRxMessage) {
            final AuthStatusRxMessage status = (AuthStatusRxMessage) statusPacket.getMessage();
            UserError.Log.d(getClass().getSimpleName(), ("Authenticated: " + status.isAuthenticated() + " " + status.isBonded()));
            if (status.isAuthenticated()) {
                this.successConsumer.call(statusValue);
            } else {
                throw new IllegalStateException("Authentication failed!!!!");
            }
        } else {
            throw new IllegalStateException("Got unexpected packet when looking for auth status: " + statusPacket.getType() + " " + JoH.bytesToHex(statusValue));
        }
    }
}
