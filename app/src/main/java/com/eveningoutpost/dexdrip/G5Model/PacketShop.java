package com.eveningoutpost.dexdrip.G5Model;

public class PacketShop {
    private Packet type;
    private TransmitterMessage message;

    public PacketShop(Packet type, TransmitterMessage message) {
        this.type = type;
        this.message = message;
    }

    public Packet getType() {
        return this.type;
    }

    public TransmitterMessage getMessage() {
        return this.message;
    }
}
