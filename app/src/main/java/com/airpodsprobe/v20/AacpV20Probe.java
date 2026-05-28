package com.airpodsprobe.v20;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class AacpV20Probe {
    private static final int AACP_PSM = 4097;
    private static final int ATT_PSM = 31;

    private static final int HANDLE_SIBLING_21 = 0x0021;
    private static final int HANDLE_SIBLING_24 = 0x0024;
    private static final int HANDLE_HEARING_CONFIG = 0x002A;
    private static final int HANDLE_CCCD_21 = 0x0022;
    private static final int HANDLE_CCCD_2A = 0x002B;

    private static final byte[] AACP_HANDSHAKE = ByteUtil.bytes(
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    );

    private final BluetoothDevice device;
    private final LogSink log;

    AacpV20Probe(BluetoothDevice device, LogSink log) {
        this.device = device;
        this.log = log;
    }

    void run() {
        log("=== AirPods AACP v20 probe started ===");
        log("Device: " + safeName(device) + " / " + device.getAddress());
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.");
        log("It does not install or replace the Xposed module.");
        log("--- v20 trigger attribution matrix for AACP 0x0053 / 0x0055 ---");
        log("v19 result: feature-flag-only sessions produced only 0x0000, 0x002B, and 0x002E; no 0x0053/0x0055 appeared.");
        log("v20 therefore varies the missing gates: AACP 0x0F notify-request mask, ATT socket timing, ATT reads, and CCCD writes 0x0022/0x002B.");
        log("v20 does not send 0x0052/0x0053/0x0054/0x0055 setter or echo candidates. It is capture/attribution only.");

        byte[] baseline2a = baselineRead();
        if (baseline2a == null) {
            log("v20 abort: could not read baseline 0x002A.");
            log("=== Probe finished ===");
            return;
        }

        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("AACP default init, no ATT", InitKind.DEFAULT_FFFFFFFF, AttPlan.NO_ATT));
        variants.add(new Variant("AACP default init + ATT open only", InitKind.DEFAULT_FFFFFFFF, AttPlan.ATT_OPEN_ONLY));
        variants.add(new Variant("AACP default init + ATT read 0x002A", InitKind.DEFAULT_FFFFFFFF, AttPlan.READ_2A_ONLY));
        variants.add(new Variant("AACP default init + CCCD 0x0022 only", InitKind.DEFAULT_FFFFFFFF, AttPlan.CCCD_22_ONLY));
        variants.add(new Variant("AACP default init + CCCD 0x002B only", InitKind.DEFAULT_FFFFFFFF, AttPlan.CCCD_2B_ONLY));
        variants.add(new Variant("AACP default init + CCCD 0x0022 then 0x002B", InitKind.DEFAULT_FFFFFFFF, AttPlan.CCCD_22_THEN_2B));
        variants.add(new Variant("AACP default init + CCCD 0x002B then 0x0022", InitKind.DEFAULT_FFFFFFFF, AttPlan.CCCD_2B_THEN_22));
        variants.add(new Variant("AACP D7 no 0x0F + CCCD 0x0022 then 0x002B", InitKind.NO_0F, AttPlan.CCCD_22_THEN_2B));
        variants.add(new Variant("AACP 0x0F mask 00 00 00 00 + CCCD pair", InitKind.MASK_00000000, AttPlan.CCCD_22_THEN_2B));
        variants.add(new Variant("AACP 0x0F mask 01 00 00 00 + CCCD pair", InitKind.MASK_01000000, AttPlan.CCCD_22_THEN_2B));
        variants.add(new Variant("AACP 0x0F mask 02 00 00 00 + CCCD pair", InitKind.MASK_02000000, AttPlan.CCCD_22_THEN_2B));
        variants.add(new Variant("AACP 0x0F mask FF FF 00 00 + CCCD pair", InitKind.MASK_FFFF0000, AttPlan.CCCD_22_THEN_2B));

        PacketRecord selected53 = null;
        PacketRecord selected55 = null;
        String selectedLabel = null;
        List<String> rows = new ArrayList<>();

        for (Variant variant : variants) {
            AacpStreamSummary s = runVariant(variant, baseline2a);
            if (s == null) {
                rows.add(variant.name + ": failed");
            } else {
                rows.add(variant.name + ": packets=" + s.packets.size()
                        + " saw53=" + s.saw53()
                        + " saw55=" + s.saw55()
                        + " saw52=" + s.saw52()
                        + " saw17=" + s.saw17()
                        + " commands=" + s.commandCountString());
                if (selected53 == null && s.first53 != null) {
                    selected53 = s.first53;
                    selected55 = s.first55;
                    selectedLabel = variant.name;
                }
                if (s.first53 != null && s.first55 != null) {
                    selected53 = s.first53;
                    selected55 = s.first55;
                    selectedLabel = variant.name + " (first 0x53+0x55 pair)";
                    log("v20 capture selection: selected first 0x0053+0x0055 pair from " + variant.name + ".");
                    // Keep running the matrix anyway so the trigger attribution is complete.
                }
            }
            sleep(1000);
        }

        log("v20 trigger matrix compact summary:");
        for (String row : rows) log("  " + row);

        if (selected53 != null) {
            log("v20 selected first 0x0053 source: " + selectedLabel);
            logPacketDetails("v20 selected 0x0053", selected53.packet);
            if (selected55 != null) logPacketDetails("v20 selected 0x0055", selected55.packet);
            log("v20 result: capture found. The next probe can use the winning trigger path and then test a single carefully chosen 0x0054/0x0055 candidate.");
        } else {
            log("v20 result: no 0x0053 capture found in this matrix. That means the missing trigger is probably outside plain AACP init + ATT CCCDs, or timing/state dependent.");
        }
        log("=== Probe finished ===");
    }

    private byte[] baselineRead() {
        BluetoothSocket aacp = null;
        BluetoothSocket att = null;
        try {
            log("v20 baseline/map: connecting AACP PSM " + AACP_PSM + ".");
            aacp = connectL2cap(device, AACP_PSM, true, "v20 baseline/map AACP");
            if (aacp == null) return null;
            AacpStreamSummary init = runAacpInit(aacp, "v20 baseline/map", InitKind.DEFAULT_FFFFFFFF, true);
            drainAacp(aacp, "v20 baseline/map post-init before ATT", 140, 2200, 80, init);

            log("v20 baseline/map: connecting ATT PSM " + ATT_PSM + ".");
            att = connectL2cap(device, ATT_PSM, false, "v20 baseline/map ATT");
            if (att == null) return null;
            drainAacp(aacp, "v20 baseline/map after ATT open", 140, 3000, 120, init);
            drainAtt(att, "v20 baseline/map ATT stale after open", 20, 1200);
            attRead(att, HANDLE_SIBLING_21, "v20 baseline sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, "v20 baseline sibling 0x0024");
            byte[] v = attRead(att, HANDLE_HEARING_CONFIG, "v20 baseline HEARING_AID_CONFIG");
            if (v != null) {
                log("v20 baseline 0x002A value: " + ByteUtil.hex(v));
                decode2a("v20 baseline 0x002A decode", v);
            }
            log("v20 baseline/map AACP stream: packets=" + init.packets.size() + ", commands=" + init.commandCountString()
                    + ", saw53=" + init.saw53() + ", saw55=" + init.saw55());
            return v;
        } catch (Exception e) {
            log("v20 baseline/map failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            closeQuietly(att);
            closeQuietly(aacp);
            log("v20 baseline/map sockets closed.");
        }
    }

    private AacpStreamSummary runVariant(Variant variant, byte[] baseline2a) {
        BluetoothSocket aacp = null;
        BluetoothSocket att = null;
        AacpStreamSummary summary = new AacpStreamSummary();
        try {
            log("--- v20 variant: " + variant.name + " ---");
            aacp = connectL2cap(device, AACP_PSM, true, "v20 " + variant.name + " AACP");
            if (aacp == null) return null;
            summary.addAll(runAacpInit(aacp, "v20 " + variant.name, variant.initKind, true));
            drainAacp(aacp, "v20 " + variant.name + " post-init pre-ATT", 140, 2800, 120, summary);

            if (variant.plan == AttPlan.NO_ATT) {
                drainAacp(aacp, "v20 " + variant.name + " no-ATT deep drain", 140, 6000, 180, summary);
                summarizeVariant(variant.name, summary);
                return summary;
            }

            att = connectL2cap(device, ATT_PSM, false, "v20 " + variant.name + " ATT");
            if (att == null) return summary;
            drainAtt(att, "v20 " + variant.name + " ATT stale after open", 20, 1000);
            drainAacp(aacp, "v20 " + variant.name + " after ATT open", 140, 4000, 140, summary);

            if (variant.plan == AttPlan.ATT_OPEN_ONLY) {
                summarizeVariant(variant.name, summary);
                return summary;
            }

            byte[] before = attRead(att, HANDLE_HEARING_CONFIG, "v20 " + variant.name + " before 0x002A");
            if (before != null) log("v20 " + variant.name + ": before equals global baseline: " + ByteUtil.equalsBytes(before, baseline2a));
            drainAacp(aacp, "v20 " + variant.name + " after initial 0x002A read", 140, 3000, 120, summary);

            if (variant.plan == AttPlan.READ_2A_ONLY) {
                summarizeVariant(variant.name, summary);
                return summary;
            }

            if (variant.plan == AttPlan.CCCD_22_ONLY) {
                enableCccdAndDrain(att, aacp, HANDLE_CCCD_21, "0x0021 notify CCCD 0x0022", summary, variant.name);
            } else if (variant.plan == AttPlan.CCCD_2B_ONLY) {
                enableCccdAndDrain(att, aacp, HANDLE_CCCD_2A, "0x002A notify CCCD 0x002B", summary, variant.name);
            } else if (variant.plan == AttPlan.CCCD_22_THEN_2B) {
                enableCccdAndDrain(att, aacp, HANDLE_CCCD_21, "0x0021 notify CCCD 0x0022", summary, variant.name);
                sleep(450);
                enableCccdAndDrain(att, aacp, HANDLE_CCCD_2A, "0x002A notify CCCD 0x002B", summary, variant.name);
            } else if (variant.plan == AttPlan.CCCD_2B_THEN_22) {
                enableCccdAndDrain(att, aacp, HANDLE_CCCD_2A, "0x002A notify CCCD 0x002B", summary, variant.name);
                sleep(450);
                enableCccdAndDrain(att, aacp, HANDLE_CCCD_21, "0x0021 notify CCCD 0x0022", summary, variant.name);
            }

            attRead(att, HANDLE_SIBLING_21, "v20 " + variant.name + " final sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, "v20 " + variant.name + " final sibling 0x0024");
            byte[] after = attRead(att, HANDLE_HEARING_CONFIG, "v20 " + variant.name + " final 0x002A");
            if (after != null) log("v20 " + variant.name + ": final 0x002A equals global baseline: " + ByteUtil.equalsBytes(after, baseline2a));
            drainAacp(aacp, "v20 " + variant.name + " final deep drain", 140, 6500, 220, summary);
            summarizeVariant(variant.name, summary);
            return summary;
        } catch (Exception e) {
            log("v20 variant " + variant.name + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return summary;
        } finally {
            closeQuietly(att);
            closeQuietly(aacp);
            log("v20 variant " + variant.name + " sockets closed.");
        }
    }

    private void enableCccdAndDrain(BluetoothSocket att, BluetoothSocket aacp, int cccdHandle, String name, AacpStreamSummary summary, String variantName) throws IOException {
        log("v20 " + variantName + ": enabling " + name + " at " + h(cccdHandle) + " = 01 00");
        boolean ok = attWriteRequest(att, cccdHandle, ByteUtil.bytes(0x01, 0x00), "v20 " + variantName + " enable " + name);
        log("v20 " + variantName + ": " + name + " write result: " + ok);
        drainAtt(att, "v20 " + variantName + " ATT after " + name, 36, 1800);
        drainAacp(aacp, "v20 " + variantName + " AACP after " + name, 140, 7600, 260, summary);
    }

    private AacpStreamSummary runAacpInit(BluetoothSocket socket, String label, InitKind kind, boolean drainAfterEach) throws IOException {
        AacpStreamSummary summary = new AacpStreamSummary();
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        sendAacp(out, AACP_HANDSHAKE, label + " handshake");
        byte[] hs = readOne(in, 1200);
        if (hs != null) {
            logPacket(label + " handshake response", hs);
            summary.add(new PacketRecord(hs, label + " handshake response"));
        } else {
            log(label + " handshake response: no packet");
        }
        if (drainAfterEach) drainAacp(socket, label + " tail after handshake", 140, 1200, 60, summary);

        sendAacp(out, aacpFeatureFlags(0xD7), label + " set feature flags D7");
        byte[] fs = readOne(in, 1400);
        if (fs != null) {
            logPacket(label + " set feature flags response", fs);
            summary.add(new PacketRecord(fs, label + " set feature flags response"));
        } else {
            log(label + " set feature flags response: no packet");
        }
        if (drainAfterEach) drainAacp(socket, label + " tail after set feature flags", 140, 1400, 80, summary);

        byte[] mask = notifyMask(kind);
        if (mask != null) {
            byte[] notify = ByteUtil.concat(ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00), mask);
            sendAacp(out, notify, label + " request notifications mask " + ByteUtil.hex(mask));
            byte[] ns = readOne(in, 1600);
            if (ns != null) {
                logPacket(label + " request notifications response", ns);
                summary.add(new PacketRecord(ns, label + " request notifications response"));
            } else {
                log(label + " request notifications response: no packet");
            }
            if (drainAfterEach) drainAacp(socket, label + " tail after request notifications", 140, 2600, 120, summary);
        } else {
            log(label + ": init kind deliberately skips AACP 0x0F notification request.");
        }
        return summary;
    }

    private byte[] notifyMask(InitKind kind) {
        switch (kind) {
            case DEFAULT_FFFFFFFF: return ByteUtil.bytes(0xFF, 0xFF, 0xFF, 0xFF);
            case MASK_00000000: return ByteUtil.bytes(0x00, 0x00, 0x00, 0x00);
            case MASK_01000000: return ByteUtil.bytes(0x01, 0x00, 0x00, 0x00);
            case MASK_02000000: return ByteUtil.bytes(0x02, 0x00, 0x00, 0x00);
            case MASK_FFFF0000: return ByteUtil.bytes(0xFF, 0xFF, 0x00, 0x00);
            case NO_0F:
            default: return null;
        }
    }

    private byte[] aacpFeatureFlags(int value) {
        return ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, value & 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
    }

    private void sendAacp(OutputStream out, byte[] frame, String label) throws IOException {
        log("AACP init/send " + label + ": " + ByteUtil.hex(frame));
        out.write(frame);
        out.flush();
    }

    private void drainAacp(BluetoothSocket socket, String label, long perReadMs, long maxTotalMs, int maxPackets, AacpStreamSummary collector) throws IOException {
        InputStream in = socket.getInputStream();
        long start = SystemClock.elapsedRealtime();
        int count = 0;
        while (count < maxPackets && SystemClock.elapsedRealtime() - start < maxTotalMs) {
            byte[] p = readOne(in, perReadMs);
            if (p == null) continue;
            count++;
            logPacket("AACP drain " + label + " packet " + count, p);
            if (collector != null) collector.add(new PacketRecord(p, label));
        }
        if (count == 0) log("AACP drain " + label + ": no packets.");
    }

    private List<byte[]> drainAtt(BluetoothSocket socket, String label, int maxPackets, long timeoutMs) throws IOException {
        List<byte[]> packets = new ArrayList<>();
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        long start = SystemClock.elapsedRealtime();
        while (packets.size() < maxPackets && SystemClock.elapsedRealtime() - start < timeoutMs) {
            byte[] p = readOne(in, 120);
            if (p == null) continue;
            packets.add(p);
            int op = p.length > 0 ? ByteUtil.u8(p[0]) : -1;
            log(label + ": drained ATT packet " + packets.size() + " opcode " + String.format(Locale.US, "0x%02X", op) + ": " + ByteUtil.hex(p));
            if (op == 0x1D) {
                log(label + ": ATT indication observed; sending Handle Value Confirmation 0x1E.");
                out.write(ByteUtil.bytes(0x1E));
                out.flush();
            }
        }
        if (packets.isEmpty()) log(label + ": no unsolicited/stale ATT packets.");
        else log(label + ": drained " + packets.size() + " ATT packet(s).");
        return packets;
    }

    private byte[] attRead(BluetoothSocket att, int handle, String label) throws IOException {
        byte[] req = ByteUtil.bytes(0x0A, handle & 0xFF, (handle >> 8) & 0xFF);
        log(String.format(Locale.US, "ATT robust read %s handle 0x%04X request: %s", label, handle, ByteUtil.hex(req)));
        OutputStream out = att.getOutputStream();
        InputStream in = att.getInputStream();
        out.write(req);
        out.flush();
        long start = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - start < 2800) {
            byte[] resp = readOne(in, 250);
            if (resp == null) continue;
            int opcode = resp.length > 0 ? resp[0] & 0xFF : -1;
            log(String.format(Locale.US, "ATT robust read 0x%04X candidate opcode 0x%02X: %s", handle, opcode, ByteUtil.hex(resp)));
            if (opcode == 0x0B) {
                byte[] value = ByteUtil.copyOfRange(resp, 1, resp.length);
                log(String.format(Locale.US, "ATT robust read 0x%04X parsed value: %s", handle, ByteUtil.hex(value)));
                return value;
            }
            if (opcode == 0x01 && resp.length >= 5 && ByteUtil.u8(resp[1]) == 0x0A && ByteUtil.u16le(resp, 2) == handle) {
                log(String.format(Locale.US, "ATT robust read 0x%04X got matching ATT Error Response: %s", handle, ByteUtil.hex(resp)));
                return null;
            }
            if (opcode == 0x1D) {
                log("ATT robust read observed indication while waiting for read; sending confirmation 0x1E.");
                out.write(ByteUtil.bytes(0x1E));
                out.flush();
            } else {
                log("ATT robust read ignored unrelated/stale packet while waiting for 0x0B/error.");
            }
        }
        log(String.format(Locale.US, "ATT robust read 0x%04X timed out.", handle));
        return null;
    }

    private boolean attWriteRequest(BluetoothSocket att, int handle, byte[] value, String label) throws IOException {
        byte[] req = ByteUtil.concat(ByteUtil.bytes(0x12, handle & 0xFF, (handle >> 8) & 0xFF), value == null ? new byte[0] : value);
        log(String.format(Locale.US, "ATT robust write-request %s handle 0x%04X request: %s", label, handle, ByteUtil.hex(req)));
        OutputStream out = att.getOutputStream();
        InputStream in = att.getInputStream();
        out.write(req);
        out.flush();
        long start = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - start < 2800) {
            byte[] resp = readOne(in, 250);
            if (resp == null) continue;
            int opcode = resp.length > 0 ? resp[0] & 0xFF : -1;
            log(String.format(Locale.US, "ATT robust write 0x%04X candidate opcode 0x%02X: %s", handle, opcode, ByteUtil.hex(resp)));
            if (opcode == 0x13) return true;
            if (opcode == 0x01 && resp.length >= 5 && ByteUtil.u8(resp[1]) == 0x12 && ByteUtil.u16le(resp, 2) == handle) {
                log(String.format(Locale.US, "ATT write-request 0x%04X got matching ATT Error Response: %s", handle, ByteUtil.hex(resp)));
                return false;
            }
            if (opcode == 0x1D) {
                log("ATT robust write observed indication while waiting for response; sending confirmation 0x1E.");
                out.write(ByteUtil.bytes(0x1E));
                out.flush();
            } else {
                log("ATT robust write ignored unrelated/stale packet while waiting for 0x13/error.");
            }
        }
        log(String.format(Locale.US, "ATT write-request 0x%04X timed out waiting for 0x13/error.", handle));
        return false;
    }

    private void summarizeVariant(String name, AacpStreamSummary summary) {
        log("v20 " + name + " summary: packets=" + summary.packets.size() + ", commands=" + summary.commandCountString());
        log("v20 " + name + " summary flags: saw0x53=" + summary.saw53()
                + ", saw0x55=" + summary.saw55()
                + ", saw0x52=" + summary.saw52()
                + ", saw0x17=" + summary.saw17());
        if (summary.first53 != null) logPacketDetails("v20 " + name + " first 0x0053", summary.first53.packet);
        if (summary.first55 != null) logPacketDetails("v20 " + name + " first 0x0055", summary.first55.packet);
        if (summary.first52 != null) logPacketDetails("v20 " + name + " first 0x0052", summary.first52.packet);
        if (summary.first17 != null) logPacketDetails("v20 " + name + " first 0x0017", summary.first17.packet);
    }

    private void decode2a(String prefix, byte[] v) {
        if (v == null) {
            log(prefix + ": <null>");
            return;
        }
        log(prefix + ": len=" + v.length + ", nonZero=" + ByteUtil.nonZero(v));
        if (v.length >= 4) {
            log(prefix + String.format(Locale.US,
                    ": byte0=0x%02X, byte1=0x%02X, declared/body-ish word at [2..3]=%d",
                    ByteUtil.u8(v[0]), ByteUtil.u8(v[1]), ByteUtil.u16le(v, 2)));
        }
        if (v.length > 0) log(prefix + String.format(Locale.US, ": last byte=0x%02X", ByteUtil.u8(v[v.length - 1])));
        if (v.length >= 4) {
            int last4 = v.length - 4;
            log(prefix + ": final float32-le candidate at last 4 bytes=" + ByteUtil.f32le(v, last4)
                    + " from " + ByteUtil.hex(ByteUtil.copyOfRange(v, last4, v.length)));
        }
    }

    private void logPacket(String label, byte[] p) {
        log(label + ": " + ByteUtil.hex(p));
        if (p == null || p.length < 6) return;
        int t = ByteUtil.u16le(p, 0);
        int s = ByteUtil.u16le(p, 2);
        int cmd = ByteUtil.u16le(p, 4);
        log(String.format(Locale.US, "  %s header: type/service? 0x%04X / 0x%04X", label, t, s));
        log(String.format(Locale.US, "  %s message/command? 0x%04X%s", label, cmd, commandHint(cmd)));
        if (cmd == 0x0004) logNested04(label, p);
        if (cmd == 0x0052 || cmd == 0x0053 || cmd == 0x0055 || cmd == 0x0017 || cmd == 0x001D) {
            logPacketDetails(label, p);
        }
    }

    private void logNested04(String label, byte[] p) {
        byte[] payload = ByteUtil.copyOfRange(p, 6, p.length);
        if (payload.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < payload.length; i++) {
            int b = ByteUtil.u8(payload[i]);
            if (b >= 0x40 && b <= 0x60) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(String.format(Locale.US, "payload[%d]=0x%02X", i, b));
            }
        }
        if (sb.length() > 0) log("  " + label + " nested 0x0004 notable bytes: " + sb);
    }

    private void logPacketDetails(String label, byte[] p) {
        if (p == null || p.length < 6) {
            log("  " + label + ": packet too short");
            return;
        }
        int cmd = ByteUtil.u16le(p, 4);
        byte[] payload = ByteUtil.copyOfRange(p, 6, p.length);
        log(String.format(Locale.US, "  %s payload (%d byte): %s", label, payload.length, ByteUtil.hex(payload)));
        if (cmd == 0x0053) {
            if (payload.length >= 2) log(String.format(Locale.US, "  %s AACP 0x0053 declared/profile length word: %d", label, ByteUtil.u16le(payload, 0)));
            if (payload.length >= 6) log("  " + label + " AACP 0x0053 vector decode: " + ByteUtil.floatSummary(payload));
        } else if (cmd == 0x0055) {
            StringBuilder dec = new StringBuilder();
            for (int i = 0; i < payload.length; i++) {
                if (i > 0) dec.append(", ");
                dec.append(payload[i] & 0xFF);
            }
            log("  " + label + " AACP 0x0055 bytes decimal: " + dec);
        } else if (cmd == 0x0052) {
            if (payload.length >= 2) log(String.format(Locale.US, "  %s AACP 0x0052 payload[0..1] as u16-le: 0x%04X", label, ByteUtil.u16le(payload, 0)));
            if (payload.length >= 5) log(String.format(Locale.US,
                    "  %s AACP 0x0052 payload bytes heuristic: lenOrSelector=%d %d, group=%d, valueA=%d, valueB=%d",
                    label, payload[0] & 0xFF, payload[1] & 0xFF, payload[2] & 0xFF, payload[3] & 0xFF, payload[4] & 0xFF));
        }
    }

    private String commandHint(int cmd) {
        switch (cmd) {
            case 0x0000: return "";
            case 0x0002: return " (Capabilities message?)";
            case 0x0004: return " (AACP message/service 4?)";
            case 0x0006: return " (Ack/keepalive/status?)";
            case 0x0008: return " (Init/status?)";
            case 0x0009: return " (Capability/status entry?)";
            case 0x000C: return " (Device status?)";
            case 0x000E: return " (Device status?)";
            case 0x0017: return " (Accessory/HID metadata?)";
            case 0x001D: return " (Accessory info?)";
            case 0x002B: return " (Feature flags response?)";
            case 0x002E: return " (Notification registration/status?)";
            case 0x004E: return " (Observed indication-side status?)";
            case 0x0052: return " (AACP 0x52 status/commit clue)";
            case 0x0053: return " (AACP 0x53 profile/vector report)";
            case 0x0054: return " (AACP 0x54 adjacent setter candidate)";
            case 0x0055: return " (AACP 0x55 status/ack candidate)";
            default: return "";
        }
    }

    private String h(int handle) {
        return String.format(Locale.US, "0x%04X", handle);
    }

    private void log(String line) {
        if (this.log != null) this.log.log(line);
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "<unknown>" : n;
        } catch (Throwable t) {
            return "<name unavailable>";
        }
    }

    @SuppressLint({"MissingPermission", "PrivateApi"})
    private BluetoothSocket connectL2cap(BluetoothDevice d, int psm, boolean secureFirst, String label) {
        List<String> order = secureFirst
                ? Arrays.asList("createL2capSocket", "createL2capChannel", "createInsecureL2capSocket", "createInsecureL2capChannel")
                : Arrays.asList("createInsecureL2capSocket", "createInsecureL2capChannel", "createL2capSocket", "createL2capChannel");
        for (String methodName : order) {
            BluetoothSocket s = null;
            try {
                log(label + ": trying " + methodName + "(" + psm + ").");
                Method m = d.getClass().getMethod(methodName, int.class);
                Object obj = m.invoke(d, psm);
                if (!(obj instanceof BluetoothSocket)) {
                    log(label + ": " + methodName + " did not return BluetoothSocket.");
                    continue;
                }
                s = (BluetoothSocket) obj;
                s.connect();
                log(label + ": PSM " + psm + " connected using " + methodName + ".");
                return s;
            } catch (Throwable t) {
                log(label + ": " + methodName + " failed: " + t.getClass().getSimpleName() + ": " + rootMessage(t));
                closeQuietly(s);
            }
        }
        log(label + ": failed to connect PSM " + psm + ".");
        return null;
    }

    private String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) r = r.getCause();
        return r.getMessage() == null ? "<no message>" : r.getMessage();
    }

    private byte[] readOne(InputStream in, long timeoutMs) throws IOException {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        byte[] buf = new byte[8192];
        while (SystemClock.elapsedRealtime() < end) {
            int avail = in.available();
            if (avail > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, Math.max(avail, 1)));
                if (n > 0) return ByteUtil.copyOfRange(buf, 0, n);
            }
            sleep(12);
        }
        return null;
    }

    private void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) {}
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private enum InitKind {
        DEFAULT_FFFFFFFF,
        NO_0F,
        MASK_00000000,
        MASK_01000000,
        MASK_02000000,
        MASK_FFFF0000
    }

    private enum AttPlan {
        NO_ATT,
        ATT_OPEN_ONLY,
        READ_2A_ONLY,
        CCCD_22_ONLY,
        CCCD_2B_ONLY,
        CCCD_22_THEN_2B,
        CCCD_2B_THEN_22
    }

    private static final class Variant {
        final String name;
        final InitKind initKind;
        final AttPlan plan;

        Variant(String name, InitKind initKind, AttPlan plan) {
            this.name = name;
            this.initKind = initKind;
            this.plan = plan;
        }
    }
}
