package com.eveningoutpost.dexdrip.G5Model;

/**
 * Created by maximilian on 17.12.17.
 */ // types of packet we receive
public enum Packet {
    NULL,
    UNKNOWN,
    AuthChallengeRxMessage,
    AuthStatusRxMessage,
    SensorRxMessage,
    VersionRequestRxMessage,
    BatteryInfoRxMessage
}
