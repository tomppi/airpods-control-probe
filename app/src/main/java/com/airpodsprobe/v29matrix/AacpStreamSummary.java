package com.airpodsprobe.v29matrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AacpStreamSummary {
    final List<PacketRecord> packets = new ArrayList<>();
    final Map<Integer, Integer> commandCounts = new LinkedHashMap<>();
    PacketRecord first53;
    PacketRecord first55;
    PacketRecord first52;
    PacketRecord first17;

    void add(PacketRecord r) {
        if (r == null) return;
        packets.add(r);
        commandCounts.put(r.command, commandCounts.containsKey(r.command) ? commandCounts.get(r.command) + 1 : 1);
        if (r.command == 0x0053 && first53 == null) first53 = r;
        if (r.command == 0x0055 && first55 == null) first55 = r;
        if (r.command == 0x0052 && first52 == null) first52 = r;
        if (r.command == 0x0017 && first17 == null) first17 = r;
    }

    void addAll(AacpStreamSummary other) {
        if (other == null) return;
        for (PacketRecord r : other.packets) add(r);
    }

    String commandCountString() {
        if (commandCounts.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, Integer> e : commandCounts.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(String.format(Locale.US, "0x%04X=%d", e.getKey(), e.getValue()));
        }
        return sb.toString();
    }

    boolean saw53() { return first53 != null; }
    boolean saw55() { return first55 != null; }
    boolean saw52() { return first52 != null; }
    boolean saw17() { return first17 != null; }
}
