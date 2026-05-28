package com.airpodsprobe.v19;

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

final class AacpV19Probe {
    private static final int AACP_PSM = 4097;
    private static final int ATT_PSM = 31;

    private static final int HANDLE_HEARING_CONFIG = 0x002A;

    private static final boolean ENABLE_RISKIER_0052_RETEST = false;
    private static final boolean ENABLE_EXACT_0055_ECHO_IF_CAPTURED = true;

    private static final byte[] AACP_HANDSHAKE = ByteUtil.bytes(
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    );

    private final BluetoothDevice device;
    private final LogSink log;

    AacpV19Probe(BluetoothDevice device, LogSink log) {
        this.device = device;
        this.log = log;
    }

    void run() {
        log("=== AirPods AACP v19 probe started ===");
        log("Device: " + safeName(device) + " / " + device.getAddress());
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.");
        log("It does not install or replace the Xposed module.");
        log("--- v19 AACP 0x4D feature-bit attribution + adjacent setter probe ---");
        log("v18 conclusion: 0x0053 is created by the 0x004D/D7 init path, not by ATT CCCDs; exact 0x0053 echo did not mutate 0x002A.");
        log("v19 therefore sweeps 0x004D feature values, tries to select a capture with both 0x0053 and 0x0055, then sends no-op 0x0054 with the exact 0x0053 payload and reads 0x002A after each candidate.");

        byte[] baseline2a = baselineRead();
        if (baseline2a == null) {
            log("v19 abort: could not read baseline 0x002A.");
            log("=== Probe finished ===");
            return;
        }

        log("--- v19 0x004D feature-bit attribution sweep ---");
        int[] featureValues = new int[] {
                0x00,
                0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80,
                0xD7, 0xD6, 0xD5, 0xD3, 0xC7, 0x97, 0x57
        };

        PacketRecord selected53 = null;
        PacketRecord selected55 = null;
        String selectedLabel = null;
        List<String> sweepRows = new ArrayList<>();

        for (int value : featureValues) {
            String label = String.format(Locale.US, "v19 sweep 0x4D feature 0x%02X no-0F", value);
            AacpStreamSummary s = runFeatureSweepOne(value, label);
            if (s == null) {
                sweepRows.add(String.format(Locale.US, "0x%02X: failed", value));
                continue;
            }
            sweepRows.add(String.format(Locale.US,
                    "0x%02X: packets=%d saw53=%s saw55=%s saw52=%s saw17=%s commands=%s",
                    value, s.packets.size(), s.saw53(), s.saw55(), s.saw52(), s.saw17(), s.commandCountString()));

            // Prefer a session containing both 0x0053 and 0x0055. Otherwise keep the first 0x0053 as a fallback.
            if (s.first53 != null && s.first55 != null) {
                selected53 = s.first53;
                selected55 = s.first55;
                selectedLabel = label + " (preferred 0x53+0x55 capture)";
                log("v19 capture selection: selected preferred 0x0053+0x0055 pair from " + label + ".");
                break;
            }
            if (selected53 == null && s.first53 != null) {
                selected53 = s.first53;
                selected55 = s.first55;
                selectedLabel = label + " (fallback first 0x53 capture)";
            }
        }

        log("v19 feature sweep compact summary:");
        for (String row : sweepRows) log("  " + row);

        if (selected53 == null) {
            log("v19 result: no 0x0053 capture was available, so adjacent setter tests are skipped.");
            log("=== Probe finished ===");
            return;
        }

        log("v19 selected capture: " + selectedLabel);
        logPacketDetails("v19 selected 0x0053", selected53.packet);
        if (selected55 != null) {
            logPacketDetails("v19 selected 0x0055", selected55.packet);
        } else {
            log("v19 selected capture did not include 0x0055; continuing with 0x0054 no-op setter candidate using the exact 0x0053 payload.");
        }

        log("--- v19 adjacent no-op setter candidate session ---");
        runAdjacentSetterSession(baseline2a, selected53, selected55);

        log("v19 result: finished. Check whether any candidate changed 0x002A, produced 0x0055, or produced a distinctive AACP ack/error.");
        log("=== Probe finished ===");
    }

    private byte[] baselineRead() {
        BluetoothSocket aacp = null;
        BluetoothSocket att = null;
        try {
            log("v19 baseline/map: trying AACP PSM " + AACP_PSM + ".");
            aacp = connectL2cap(device, AACP_PSM, true, "v19 baseline/map AACP");
            if (aacp == null) return null;
            AacpStreamSummary init = runStandardInit(aacp, "v19 baseline/map", 0xD7, ByteUtil.bytes(0xFF, 0xFF, 0xFF, 0xFF), true);
            drainAacp(aacp, "v19 baseline/map post-init drain", 250, 5000, 80, init);

            log("v19 baseline/map: trying ATT PSM " + ATT_PSM + ".");
            att = connectL2cap(device, ATT_PSM, false, "v19 baseline/map ATT");
            if (att == null) return null;

            byte[] v = attRead(att, HANDLE_HEARING_CONFIG, "v19 baseline HEARING_AID_CONFIG");
            if (v != null) {
                log("v19 baseline 0x002A value: " + ByteUtil.hex(v));
                decode2a("v19 baseline 0x002A decode", v);
            }
            return v;
        } catch (Exception e) {
            log("v19 baseline/map failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            closeQuietly(att);
            closeQuietly(aacp);
            log("v19 baseline/map sockets closed.");
        }
    }

    private AacpStreamSummary runFeatureSweepOne(int featureValue, String label) {
        BluetoothSocket aacp = null;
        try {
            log(label + ": trying createL2capSocket(" + AACP_PSM + ").");
            aacp = connectL2cap(device, AACP_PSM, true, label + " AACP");
            if (aacp == null) return null;

            AacpStreamSummary summary = new AacpStreamSummary();
            OutputStream out = aacp.getOutputStream();

            sendAacp(out, AACP_HANDSHAKE, label + " handshake");
            byte[] r = readOne(aacp.getInputStream(), 1200);
            if (r != null) {
                logPacket(label + " handshake response", r);
                summary.add(new PacketRecord(r, label + " handshake response"));
            } else {
                log(label + " handshake response: no packet");
            }
            drainAacp(aacp, label + " tail after handshake", 250, 900, 40, summary);

            byte[] feature = aacpFeatureFlags(featureValue);
            sendAacp(out, feature, label + " set feature flags " + String.format(Locale.US, "%02X", featureValue));
            byte[] fr = readOne(aacp.getInputStream(), 1200);
            if (fr != null) {
                logPacket(label + " set feature flags response", fr);
                summary.add(new PacketRecord(fr, label + " set feature flags response"));
            } else {
                log(label + " set feature flags response: no packet");
            }
            drainAacp(aacp, label + " tail after set feature flags", 250, 1800, 80, summary);

            log(String.format(Locale.US,
                    "%s summary: packets=%d, commands=%s", label, summary.packets.size(), summary.commandCountString()));
            log(String.format(Locale.US,
                    "%s summary flags: saw0x53=%s, saw0x55=%s, saw0x52=%s, saw0x17=%s",
                    label, summary.saw53(), summary.saw55(), summary.saw52(), summary.saw17()));
            if (summary.first53 != null) logPacketDetails(label + " first 0x0053", summary.first53.packet);
            if (summary.first55 != null) logPacketDetails(label + " first 0x0055", summary.first55.packet);
            return summary;
        } catch (Exception e) {
            log(label + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            closeQuietly(aacp);
            log(label + " socket closed.");
            sleep(700);
        }
    }

    private void runAdjacentSetterSession(byte[] baseline2a, PacketRecord selected53, PacketRecord selected55) {
        BluetoothSocket aacp = null;
        BluetoothSocket att = null;
        try {
            log("v19 setter session: connecting AACP.");
            aacp = connectL2cap(device, AACP_PSM, true, "v19 setter session AACP");
            if (aacp == null) return;
            AacpStreamSummary init = runStandardInit(aacp, "v19 setter session", 0xD7, ByteUtil.bytes(0xFF, 0xFF, 0xFF, 0xFF), true);

            log("v19 setter session: connecting ATT.");
            att = connectL2cap(device, ATT_PSM, false, "v19 setter session ATT");
            if (att == null) return;
            drainAacp(aacp, "v19 setter session post-init settle", 250, 5000, 100, init);
            log(String.format(Locale.US,
                    "v19 setter session init stream summary: packets=%d, commands=%s", init.packets.size(), init.commandCountString()));
            log(String.format(Locale.US,
                    "v19 setter session init stream flags: saw0x53=%s, saw0x55=%s, saw0x52=%s, saw0x17=%s",
                    init.saw53(), init.saw55(), init.saw52(), init.saw17()));

            byte[] before = attRead(att, HANDLE_HEARING_CONFIG, "v19 setter session before");
            if (before == null) return;
            log("v19 setter session: before equals baseline: " + ByteUtil.equalsBytes(before, baseline2a));

            byte[] payload53 = selected53.payload();
            byte[] frame54 = aacpFrame(0x0054, payload53);
            sendCandidateAndReadback(aacp, att, frame54, baseline2a, "v19 candidate 0x0054 with exact 0x0053 payload");

            if (ENABLE_EXACT_0055_ECHO_IF_CAPTURED && selected55 != null) {
                byte[] frame55 = aacpFrame(0x0055, selected55.payload());
                sendCandidateAndReadback(aacp, att, frame55, baseline2a, "v19 exact captured 0x0055 echo");
            } else if (selected55 == null) {
                log("v19 exact 0x0055 echo skipped: no 0x0055 capture selected.");
            }

            if (ENABLE_RISKIER_0052_RETEST) {
                byte[] frame52 = aacpFrame(0x0052, payload53);
                sendCandidateAndReadback(aacp, att, frame52, baseline2a, "v19 optional/riskier 0x0052 with exact 0x0053 payload");
            } else {
                log("v19 optional 0x0052 retest skipped by default. Set ENABLE_RISKIER_0052_RETEST=true in AacpV19Probe.java to run it.");
            }

            byte[] fin = attRead(att, HANDLE_HEARING_CONFIG, "v19 setter session final");
            if (fin != null) {
                log("v19 setter session final value equals baseline: " + ByteUtil.equalsBytes(fin, baseline2a));
                decode2a("v19 setter session final 0x002A decode", fin);
            }
        } catch (Exception e) {
            log("v19 setter session failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            closeQuietly(att);
            closeQuietly(aacp);
            log("v19 setter session sockets closed.");
        }
    }

    private void sendCandidateAndReadback(BluetoothSocket aacp, BluetoothSocket att, byte[] frame, byte[] baseline2a, String label) throws IOException {
        OutputStream out = aacp.getOutputStream();
        InputStream in = aacp.getInputStream();
        log("AACP send " + label + ": " + ByteUtil.hex(frame));
        out.write(frame);
        out.flush();

        byte[] immediate = readOne(in, 1200);
        if (immediate != null) {
            logPacket("AACP immediate response to " + label, immediate);
        } else {
            log("AACP immediate response to " + label + ": no packet.");
        }

        AacpStreamSummary tail = new AacpStreamSummary();
        drainAacp(aacp, "AACP response tail for " + label, 250, 2500, 50, tail);
        log(String.format(Locale.US,
                "%s response summary: packets=%d, commands=%s, saw0x53=%s, saw0x55=%s, saw0x52=%s, saw0x17=%s",
                label, tail.packets.size(), tail.commandCountString(), tail.saw53(), tail.saw55(), tail.saw52(), tail.saw17()));
        if (tail.first55 != null) logPacketDetails(label + " first 0x0055 response", tail.first55.packet);
        if (tail.first52 != null) logPacketDetails(label + " first 0x0052 response", tail.first52.packet);

        byte[] after = attRead(att, HANDLE_HEARING_CONFIG, "readback after " + label);
        if (after != null) {
            boolean changed = !ByteUtil.equalsBytes(after, baseline2a);
            log(label + ": 0x002A changed from baseline: " + changed);
            if (changed) {
                log(label + ": changed 0x002A value: " + ByteUtil.hex(after));
                decode2a(label + " changed 0x002A decode", after);
            }
        }
    }

    private AacpStreamSummary runStandardInit(BluetoothSocket socket, String label, int featureValue, byte[] notifyMask, boolean drainAfterEach) throws IOException {
        AacpStreamSummary summary = new AacpStreamSummary();
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        sendAacp(out, AACP_HANDSHAKE, label + " handshake");
        byte[] hs = readOne(in, 1500);
        if (hs != null) {
            logPacket(label + " handshake response", hs);
            summary.add(new PacketRecord(hs, label + " handshake response"));
        } else {
            log(label + " handshake response: no packet");
        }
        if (drainAfterEach) drainAacp(socket, label + " tail after handshake", 250, 1500, 80, summary);

        byte[] feature = aacpFeatureFlags(featureValue);
        sendAacp(out, feature, label + " set feature flags " + String.format(Locale.US, "%02X", featureValue));
        byte[] fs = readOne(in, 1500);
        if (fs != null) {
            logPacket(label + " set feature flags response", fs);
            summary.add(new PacketRecord(fs, label + " set feature flags response"));
        } else {
            log(label + " set feature flags response: no packet");
        }
        if (drainAfterEach) drainAacp(socket, label + " tail after set feature flags", 250, 1500, 80, summary);

        if (notifyMask != null) {
            byte[] notify = ByteUtil.concat(ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00), notifyMask);
            sendAacp(out, notify, label + " request notifications mask " + ByteUtil.hex(notifyMask));
            byte[] ns = readOne(in, 1500);
            if (ns != null) {
                logPacket(label + " request notifications response", ns);
                summary.add(new PacketRecord(ns, label + " request notifications response"));
            } else {
                log(label + " request notifications response: no packet");
            }
            if (drainAfterEach) drainAacp(socket, label + " tail after request notifications", 250, 2500, 100, summary);
        }
        return summary;
    }

    private byte[] aacpFeatureFlags(int value) {
        return ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, value & 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
    }

    private byte[] aacpFrame(int command, byte[] payload) {
        return ByteUtil.concat(ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, command & 0xFF, (command >> 8) & 0xFF), payload == null ? new byte[0] : payload);
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
            if (p == null) break;
            count++;
            logPacket("AACP drain " + label + " packet " + count, p);
            if (collector != null) collector.add(new PacketRecord(p, label));
        }
        if (count == 0) log("AACP drain " + label + ": no packets.");
    }

    private byte[] attRead(BluetoothSocket att, int handle, String label) throws IOException {
        byte[] req = ByteUtil.bytes(0x0A, handle & 0xFF, (handle >> 8) & 0xFF);
        log(String.format(Locale.US, "ATT robust read %s handle 0x%04X request: %s", label, handle, ByteUtil.hex(req)));
        OutputStream out = att.getOutputStream();
        out.write(req);
        out.flush();
        byte[] resp = readOne(att.getInputStream(), 1800);
        if (resp == null) {
            log(String.format(Locale.US, "ATT robust read 0x%04X: no response", handle));
            return null;
        }
        int opcode = resp.length > 0 ? resp[0] & 0xFF : -1;
        log(String.format(Locale.US, "ATT robust read 0x%04X candidate opcode 0x%02X: %s", handle, opcode, ByteUtil.hex(resp)));
        if (opcode == 0x0B) {
            byte[] value = ByteUtil.copyOfRange(resp, 1, resp.length);
            log(String.format(Locale.US, "ATT robust read 0x%04X parsed value: %s", handle, ByteUtil.hex(value)));
            return value;
        }
        if (opcode == 0x01) {
            log(String.format(Locale.US, "ATT robust read 0x%04X got ATT Error Response: %s", handle, ByteUtil.hex(resp)));
            return null;
        }
        log(String.format(Locale.US, "ATT robust read 0x%04X got unexpected response; not parsing as value.", handle));
        return null;
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
        if (cmd == 0x0052 || cmd == 0x0053 || cmd == 0x0055) {
            logPacketDetails(label, p);
        }
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
            if (payload.length >= 6) {
                log("  " + label + " AACP 0x0053 vector decode: " + ByteUtil.floatSummary(payload));
            }
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
}
