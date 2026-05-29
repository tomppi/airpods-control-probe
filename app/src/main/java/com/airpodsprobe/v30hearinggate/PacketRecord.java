package com.airpodsprobe.v30hearinggate;

final class PacketRecord {
    final byte[] packet;
    final int command;
    final String source;

    PacketRecord(byte[] packet, String source) {
        this.packet = packet;
        this.source = source;
        this.command = packet != null && packet.length >= 6 ? ByteUtil.u16le(packet, 4) : -1;
    }

    byte[] payload() {
        if (packet == null || packet.length <= 6) return new byte[0];
        return ByteUtil.copyOfRange(packet, 6, packet.length);
    }
}
