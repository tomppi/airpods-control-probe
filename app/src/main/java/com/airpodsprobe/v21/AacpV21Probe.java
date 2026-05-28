package com.airpodsprobe.v21;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class AacpV21Probe {
    private static final int AACP_PSM = 4097;
    private static final int ATT_PSM = 31;

    private static final int HANDLE_SIBLING_21 = 0x0021;
    private static final int HANDLE_SIBLING_24 = 0x0024;
    private static final int HANDLE_HEARING_CONFIG = 0x002A;
    private static final int HANDLE_CCCD_21 = 0x0022;

    private static final byte[] AACP_HANDSHAKE = ByteUtil.bytes(
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    );

    private final BluetoothDevice device;
    private final LogSink log;

    AacpV21Probe(BluetoothDevice device, LogSink log) {
        this.device = device;
        this.log = log;
    }

    void run() {
        log("=== AirPods AACP v21 probe started ===");
        log("Device: " + safeName(device) + " / " + device.getAddress());
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.");
        log("It does not install or replace the Xposed module.");
        log("--- v21 single-candidate 0x0054 no-op echo setter test ---");
        log("v20 result: ATT CCCD 0x0022 was the clean winning gate for AACP 0x0052/0x0053/0x0055.");
        log("v21 uses only that winning path, captures the current 0x0053 vector, then sends exactly one AACP 0x0054 echo using the captured 0x0053 payload unchanged.");
        log("v21 does not send 0x0052, 0x0053, or 0x0055 candidates. Any 0x0055 seen after the 0x0054 frame is treated as a response/ack candidate.");

        BluetoothSocket aacp = null;
        BluetoothSocket att = null;
        AacpStreamSummary preSetter = new AacpStreamSummary();
        AacpStreamSummary afterCccd = new AacpStreamSummary();
        AacpStreamSummary postSetter = new AacpStreamSummary();
        byte[] before2a = null;
        byte[] after2a = null;

        try {
            log("v21: connecting AACP PSM " + AACP_PSM + ".");
            aacp = connectL2cap(device, AACP_PSM, true, "v21 AACP");
            if (aacp == null) {
                log("v21 abort: failed to connect AACP PSM " + AACP_PSM + ".");
                return;
            }

            preSetter.addAll(runAacpInit(aacp, "v21 winning-path init"));
            drainAacp(aacp, "v21 post-init before ATT", 140, 2600, 120, preSetter);

            log("v21: connecting ATT PSM " + ATT_PSM + ".");
            att = connectL2cap(device, ATT_PSM, false, "v21 ATT");
            if (att == null) {
                log("v21 abort: failed to connect ATT PSM " + ATT_PSM + ".");
                return;
            }

            drainAtt(att, "v21 ATT stale after open", 20, 1200);
            drainAacp(aacp, "v21 after ATT open", 140, 3600, 120, preSetter);

            attRead(att, HANDLE_SIBLING_21, "v21 pre-setter sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, "v21 pre-setter sibling 0x0024");
            before2a = attRead(att, HANDLE_HEARING_CONFIG, "v21 pre-setter 0x002A");
            if (before2a != null) {
                log("v21 pre-setter 0x002A value: " + ByteUtil.hex(before2a));
                decode2a("v21 pre-setter 0x002A decode", before2a);
            }
            drainAacp(aacp, "v21 after initial ATT reads", 140, 3000, 120, preSetter);

            log("v21: enabling the winning trigger only: 0x0021 notify CCCD 0x0022 at handle 0x0022 = 01 00.");
            boolean cccdOk = attWriteRequest(att, HANDLE_CCCD_21, ByteUtil.bytes(0x01, 0x00), "v21 enable winning CCCD 0x0022");
            log("v21: winning CCCD 0x0022 write result: " + cccdOk);
            drainAtt(att, "v21 ATT after winning CCCD 0x0022", 36, 1800);
            drainAacp(aacp, "v21 AACP after winning CCCD 0x0022", 140, 10000, 160, afterCccd);

            PacketRecord echoSource = afterCccd.first53;
            String echoSourceLabel = "post-CCCD 0x0053";
            if (echoSource == null && preSetter.first53 != null) {
                echoSource = preSetter.first53;
                echoSourceLabel = "early/pre-setter 0x0053";
                log("v21 warning: no 0x0053 arrived after the CCCD 0x0022 trigger, but an earlier current-session 0x0053 was observed. Using that as the exact no-op echo source.");
            }

            summarizeStream("v21 pre-setter init/open/read stream", preSetter);
            summarizeStream("v21 after winning CCCD 0x0022 stream", afterCccd);

            if (echoSource == null) {
                log("v21 abort: no current-session AACP 0x0053 payload was captured, so no 0x0054 echo was sent. This avoids sending a stale or non-no-op vector.");
                return;
            }

            byte[] echoPayload = echoSource.payload();
            logPacketDetails("v21 selected " + echoSourceLabel + " echo source", echoSource.packet);
            if (!validate53Payload(echoPayload)) {
                log("v21 abort: selected 0x0053 payload did not look like the expected 134-byte vector payload, so no 0x0054 echo was sent.");
                return;
            }

            byte[] echo54 = ByteUtil.concat(ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, 0x54, 0x00), echoPayload);
            log("v21: sending exactly one AACP 0x0054 no-op echo candidate. Frame bytes: " + ByteUtil.hex(echo54));
            sendAacp(aacp.getOutputStream(), echo54, "v21 0x0054 no-op echo setter");

            byte[] immediate = readOne(aacp.getInputStream(), 1800);
            if (immediate != null) {
                logPacket("v21 immediate packet after 0x0054", immediate);
                postSetter.add(new PacketRecord(immediate, "immediate after 0x0054"));
            } else {
                log("v21 immediate packet after 0x0054: no packet.");
            }
            drainAacp(aacp, "v21 after 0x0054 no-op echo", 140, 9000, 160, postSetter);
            drainAtt(att, "v21 ATT after 0x0054 no-op echo", 36, 1800);

            attRead(att, HANDLE_SIBLING_21, "v21 post-setter sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, "v21 post-setter sibling 0x0024");
            after2a = attRead(att, HANDLE_HEARING_CONFIG, "v21 post-setter 0x002A");
            if (after2a != null) {
                log("v21 post-setter 0x002A value: " + ByteUtil.hex(after2a));
                decode2a("v21 post-setter 0x002A decode", after2a);
            }

            drainAacp(aacp, "v21 final deep drain after post-setter reads", 140, 6000, 160, postSetter);
            summarizeStream("v21 post-0x0054 stream", postSetter);

            log("v21 result summary:");
            log("  selected echo source: " + echoSourceLabel);
            log("  saw post-0x0054 0x0055 response candidate: " + postSetter.saw55());
            log("  saw post-0x0054 0x0053 refresh: " + postSetter.saw53());
            log("  saw post-0x0054 0x0052 status: " + postSetter.saw52());
            if (before2a != null && after2a != null) {
                log("  ATT 0x002A changed after no-op echo: " + (!ByteUtil.equalsBytes(before2a, after2a)));
            } else {
                log("  ATT 0x002A before/after comparison unavailable: before=" + (before2a != null) + ", after=" + (after2a != null));
            }
            if (postSetter.first55 != null) logPacketDetails("v21 first post-0x0054 0x0055", postSetter.first55.packet);
            if (postSetter.first53 != null) logPacketDetails("v21 first post-0x0054 0x0053", postSetter.first53.packet);
            if (postSetter.first52 != null) logPacketDetails("v21 first post-0x0054 0x0052", postSetter.first52.packet);

            if (postSetter.saw55()) {
                log("v21 interpretation: AACP 0x0054 is a strong setter/commit candidate because a 0x0055 response appeared after the no-op 0x0054 echo.");
            } else {
                log("v21 interpretation: no post-0x0054 0x0055 was observed in this run. Check whether 0x0053 refreshed or whether the no-op was silently accepted/ignored.");
            }
        } catch (Exception e) {
            log("v21 probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            closeQuietly(att);
            closeQuietly(aacp);
            log("v21 sockets closed.");
            log("=== Probe finished ===");
        }
    }

    private AacpStreamSummary runAacpInit(BluetoothSocket socket, String label) throws IOException {
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
        drainAacp(socket, label + " tail after handshake", 140, 1400, 80, summary);

        sendAacp(out, aacpFeatureFlags(0xD7), label + " set feature flags D7");
        byte[] fs = readOne(in, 1400);
        if (fs != null) {
            logPacket(label + " set feature flags response", fs);
            summary.add(new PacketRecord(fs, label + " set feature flags response"));
        } else {
            log(label + " set feature flags response: no packet");
        }
        drainAacp(socket, label + " tail after set feature flags", 140, 1600, 80, summary);

        byte[] notify = ByteUtil.concat(ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00), ByteUtil.bytes(0xFF, 0xFF, 0xFF, 0xFF));
        sendAacp(out, notify, label + " request notifications mask FF FF FF FF");
        byte[] ns = readOne(in, 1600);
        if (ns != null) {
            logPacket(label + " request notifications response", ns);
            summary.add(new PacketRecord(ns, label + " request notifications response"));
        } else {
            log(label + " request notifications response: no packet");
        }
        drainAacp(socket, label + " tail after request notifications", 140, 2800, 120, summary);
        return summary;
    }

    private byte[] aacpFeatureFlags(int value) {
        return ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, value & 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
    }

    private boolean validate53Payload(byte[] payload) {
        if (payload == null) return false;
        int declared = payload.length >= 2 ? ByteUtil.u16le(payload, 0) : -1;
        log("v21 selected 0x0053 payload validation: len=" + payload.length + ", declared/body-ish=" + declared + ", float summary=" + ByteUtil.floatSummary(payload));
        if (payload.length < 10) return false;
        if (declared <= 0) return false;
        if (declared != payload.length - 2) {
            log("v21 warning: 0x0053 declared/body-ish length does not equal payloadLen-2. Continuing because this is an echo of an observed packet, not a synthetic payload.");
        }
        return true;
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

    private void drainAtt(BluetoothSocket socket, String label, int maxPackets, long timeoutMs) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        long start = SystemClock.elapsedRealtime();
        int count = 0;
        while (count < maxPackets && SystemClock.elapsedRealtime() - start < timeoutMs) {
            byte[] p = readOne(in, 120);
            if (p == null) continue;
            count++;
            int op = p.length > 0 ? ByteUtil.u8(p[0]) : -1;
            log(label + ": drained ATT packet " + count + " opcode " + String.format(Locale.US, "0x%02X", op) + ": " + ByteUtil.hex(p));
            if (op == 0x1D) {
                log(label + ": ATT indication observed; sending Handle Value Confirmation 0x1E.");
                out.write(ByteUtil.bytes(0x1E));
                out.flush();
            }
        }
        if (count == 0) log(label + ": no unsolicited/stale ATT packets.");
        else log(label + ": drained " + count + " ATT packet(s).");
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

    private void summarizeStream(String name, AacpStreamSummary summary) {
        log(name + " summary: packets=" + summary.packets.size() + ", commands=" + summary.commandCountString());
        log(name + " summary flags: saw0x53=" + summary.saw53()
                + ", saw0x55=" + summary.saw55()
                + ", saw0x52=" + summary.saw52()
                + ", saw0x17=" + summary.saw17());
        if (summary.first53 != null) logPacketDetails(name + " first 0x0053", summary.first53.packet);
        if (summary.first55 != null) logPacketDetails(name + " first 0x0055", summary.first55.packet);
        if (summary.first52 != null) logPacketDetails(name + " first 0x0052", summary.first52.packet);
        if (summary.first17 != null) logPacketDetails(name + " first 0x0017", summary.first17.packet);
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
            case 0x0054: return " (AACP 0x54 setter/echo candidate)";
            case 0x0055: return " (AACP 0x55 status/ack candidate)";
            default: return "";
        }
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
}
