package com.airpodsprobe.v28control;

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

final class AacpV28ControlProbe {
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

    AacpV28ControlProbe(BluetoothDevice device, LogSink log) {
        this.device = device;
        this.log = log;
    }

    void run() {
        log("=== AirPods AACP v28 control-map probe started ===");
        log("Device: " + safeName(device) + " / " + device.getAddress());
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.");
        log("It does not install or replace the Xposed module.");
        log("--- v28 pure control probe: isolate whether AACP 0x0052 is tied to original-payload 0x0054 ---");
        log("v28 sends no canary, no semantic mutation, no ATT 0x002A writes, and no AACP 0x0052/0x0053/0x0055 candidates.");
        log("The only post-capture AACP stimuli are: baseline wait, benign notification refresh, original captured 0x0053 payload via 0x0054, then one post-0x0054 benign refresh.");
        log("Every observed packet in the control blocks is logged with relative milliseconds since the block stimulus/start.");

        boolean completed = false;
        final int attempts = 2;
        for (int i = 1; i <= attempts; i++) {
            completed = runControlAttempt(i, attempts);
            if (completed) break;
            if (i < attempts) {
                log("v28 control: attempt " + i + " did not reach the isolated control blocks. Sleeping briefly, then retrying once.");
                sleep(1300);
            }
        }
        if (!completed) {
            log("v28 final result: no guarded current-session 0x0053 payload was available, so the original-payload 0x0054 control block was not sent.");
        }
        log("=== Probe finished ===");
    }

    private boolean runControlAttempt(int attempt, int totalAttempts) {
        BluetoothSocket aacp = null;
        BluetoothSocket att = null;
        AacpStreamSummary preCccd = new AacpStreamSummary();
        AacpStreamSummary afterCccd = new AacpStreamSummary();
        AacpStreamSummary afterPostCccdReads = new AacpStreamSummary();
        AacpStreamSummary blockA_baseline = new AacpStreamSummary();
        AacpStreamSummary blockB_refreshOnly = new AacpStreamSummary();
        AacpStreamSummary blockC_original54 = new AacpStreamSummary();
        AacpStreamSummary blockD_post54Refresh = new AacpStreamSummary();
        byte[] before2a = null;
        byte[] postCccd2a = null;
        byte[] post54_2a = null;
        byte[] final2a = null;
        byte[] selected53Payload = null;
        boolean original54Sent = false;

        String prefix = "v28 attempt " + attempt + "/" + totalAttempts;
        try {
            log("--- " + prefix + ": starting known-good current-session capture path ---");
            log(prefix + ": connecting AACP PSM " + AACP_PSM + ".");
            aacp = connectL2cap(device, AACP_PSM, true, prefix + " AACP");
            if (aacp == null) {
                log(prefix + " abort: failed to connect AACP PSM " + AACP_PSM + ".");
                return false;
            }

            preCccd.addAll(runAacpInit(aacp, prefix + " winning-path init"));
            drainAacp(aacp, prefix + " post-init before ATT", 140, 2800, 140, preCccd);

            log(prefix + ": connecting ATT PSM " + ATT_PSM + " for read-only context only.");
            att = connectL2cap(device, ATT_PSM, false, prefix + " ATT");
            if (att == null) {
                log(prefix + " abort: failed to connect ATT PSM " + ATT_PSM + ".");
                return false;
            }

            drainAtt(att, prefix + " ATT stale after open", 24, 1300);
            drainAacp(aacp, prefix + " after ATT open", 140, 4200, 160, preCccd);

            attRead(att, HANDLE_SIBLING_21, prefix + " pre-CCCD sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, prefix + " pre-CCCD sibling 0x0024");
            before2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " pre-CCCD 0x002A read-only context");
            if (before2a != null) {
                log(prefix + " pre-CCCD 0x002A value: " + ByteUtil.hex(before2a));
                decode2a(prefix + " pre-CCCD 0x002A decode", before2a);
            }
            drainAacp(aacp, prefix + " after initial ATT reads", 140, 3300, 140, preCccd);

            log(prefix + ": enabling winning trigger: 0x0021 notify CCCD 0x0022 at handle 0x0022 = 01 00.");
            boolean cccdOk = attWriteRequest(att, HANDLE_CCCD_21, ByteUtil.bytes(0x01, 0x00), prefix + " enable winning CCCD 0x0022");
            log(prefix + ": winning CCCD 0x0022 write result: " + cccdOk);
            drainAtt(att, prefix + " ATT after winning CCCD 0x0022", 40, 1900);
            drainAacp(aacp, prefix + " AACP direct drain after winning CCCD 0x0022", 140, 12000, 240, afterCccd);

            log(prefix + ": continuing with v20-style post-CCCD ATT reads before any v28 control block.");
            attRead(att, HANDLE_SIBLING_21, prefix + " post-CCCD sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, prefix + " post-CCCD sibling 0x0024");
            postCccd2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " post-CCCD 0x002A read-only context");
            if (postCccd2a != null) {
                log(prefix + " post-CCCD 0x002A value: " + ByteUtil.hex(postCccd2a));
                decode2a(prefix + " post-CCCD 0x002A decode", postCccd2a);
                if (before2a != null) log(prefix + " post-CCCD 0x002A equals pre-CCCD: " + ByteUtil.equalsBytes(before2a, postCccd2a));
            }
            drainAacp(aacp, prefix + " final capture deep drain after post-CCCD reads", 140, 14500, 320, afterPostCccdReads);

            summarizeStream(prefix + " pre-CCCD stream", preCccd);
            summarizeStream(prefix + " direct post-CCCD stream", afterCccd);
            summarizeStream(prefix + " post-CCCD-read deep stream", afterPostCccdReads);

            PacketRecord context53 = chooseCurrentSession53(preCccd, afterCccd, afterPostCccdReads);
            String context53Label = chooseCurrentSession53Label(preCccd, afterCccd, afterPostCccdReads);
            if (context53 == null) {
                log(prefix + " abort: no current-session AACP 0x0053 vector was captured. The original-payload 0x0054 control block will not be sent.");
                return false;
            }
            selected53Payload = context53.payload();
            logPacketDetails(prefix + " selected " + context53Label + " original-payload 0x0054 source", context53.packet);
            if (!validate53ForOriginal54(selected53Payload)) {
                log(prefix + " abort: selected AACP 0x0053 payload failed the conservative original-0x0054 guard. No 0x0054 frame will be sent.");
                return false;
            }
            log(prefix + " selected original 0x0053 payload length=" + selected53Payload.length + ", bytes=" + ByteUtil.hex(selected53Payload));

            // Block A: no stimulus, just wait and drain to determine whether 0x0052 is spontaneous.
            log("--- " + prefix + " BLOCK A: baseline wait, no AACP/ATT stimulus ---");
            long blockAStart = SystemClock.elapsedRealtime();
            drainAacpRelative(aacp, prefix + " BLOCK A baseline no-stimulus", blockAStart, "block A start", 160, 20000, 320, blockA_baseline);
            summarizeStream(prefix + " BLOCK A baseline no-stimulus stream", blockA_baseline);

            // Block B: benign refresh only.
            log("--- " + prefix + " BLOCK B: benign refresh only, AACP 0x000F FF FF FF FF ---");
            byte[] refresh = notificationRequestAll();
            long blockBStimulus = SystemClock.elapsedRealtime();
            sendAacp(aacp.getOutputStream(), refresh, prefix + " BLOCK B refresh-only request notifications mask FF FF FF FF");
            readImmediateRelative(aacp, prefix + " BLOCK B immediate packet after refresh-only request", blockBStimulus, "block B refresh", blockB_refreshOnly);
            drainAacpRelative(aacp, prefix + " BLOCK B after refresh-only request", blockBStimulus, "block B refresh", 160, 10000, 240, blockB_refreshOnly);
            summarizeStream(prefix + " BLOCK B refresh-only stream", blockB_refreshOnly);

            // Block C: exact original 0x0053 payload via AACP 0x0054.
            log("--- " + prefix + " BLOCK C: exact original current-session 0x0053 payload via AACP 0x0054 ---");
            byte[] original54Frame = aacp54FullPayload(selected53Payload);
            log(prefix + " BLOCK C original 0x0054 frame bytes: " + ByteUtil.hex(original54Frame));
            long blockCStimulus = SystemClock.elapsedRealtime();
            original54Sent = true;
            sendAacp(aacp.getOutputStream(), original54Frame, prefix + " BLOCK C original 0x0053 via 0x0054 full-payload");
            readImmediateRelative(aacp, prefix + " BLOCK C immediate packet after original 0x0054", blockCStimulus, "block C original 0x0054", blockC_original54);
            drainAacpRelative(aacp, prefix + " BLOCK C after original 0x0054", blockCStimulus, "block C original 0x0054", 160, 20000, 360, blockC_original54);
            drainAtt(att, prefix + " ATT after BLOCK C original 0x0054", 40, 2200);
            post54_2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " post-BLOCK-C 0x002A read-only context");
            if (post54_2a != null) {
                log(prefix + " post-BLOCK-C 0x002A value: " + ByteUtil.hex(post54_2a));
                decode2a(prefix + " post-BLOCK-C 0x002A decode", post54_2a);
                if (postCccd2a != null) log(prefix + " post-BLOCK-C 0x002A equals pre-control 0x002A: " + ByteUtil.equalsBytes(postCccd2a, post54_2a));
            }
            drainAacpRelative(aacp, prefix + " BLOCK C final deep drain after post-0x0054 ATT read", blockCStimulus, "block C original 0x0054", 160, 6000, 220, blockC_original54);
            summarizeStream(prefix + " BLOCK C original-0x0054 stream", blockC_original54);

            // Block D: refresh after original 0x0054.
            log("--- " + prefix + " BLOCK D: benign refresh after original 0x0054 ---");
            long blockDStimulus = SystemClock.elapsedRealtime();
            sendAacp(aacp.getOutputStream(), refresh, prefix + " BLOCK D post-0x0054 refresh request notifications mask FF FF FF FF");
            readImmediateRelative(aacp, prefix + " BLOCK D immediate packet after post-0x0054 refresh", blockDStimulus, "block D post-0x0054 refresh", blockD_post54Refresh);
            drainAacpRelative(aacp, prefix + " BLOCK D after post-0x0054 refresh", blockDStimulus, "block D post-0x0054 refresh", 160, 12000, 260, blockD_post54Refresh);
            final2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " final 0x002A read-only context after BLOCK D");
            if (final2a != null) {
                log(prefix + " final 0x002A value: " + ByteUtil.hex(final2a));
                decode2a(prefix + " final 0x002A decode", final2a);
                if (postCccd2a != null) log(prefix + " final 0x002A equals pre-control 0x002A: " + ByteUtil.equalsBytes(postCccd2a, final2a));
            }
            drainAacpRelative(aacp, prefix + " final deep drain after BLOCK D and final ATT read", blockDStimulus, "block D post-0x0054 refresh", 160, 5000, 180, blockD_post54Refresh);
            summarizeStream(prefix + " BLOCK D post-0x0054-refresh stream", blockD_post54Refresh);

            log(prefix + " result matrix:");
            logBlockResult("BLOCK A baseline", blockA_baseline, selected53Payload);
            logBlockResult("BLOCK B refresh-only", blockB_refreshOnly, selected53Payload);
            logBlockResult("BLOCK C original-0x0054", blockC_original54, selected53Payload);
            logBlockResult("BLOCK D refresh-after-0x0054", blockD_post54Refresh, selected53Payload);
            log("  original AACP 0x0054 full-payload sent: " + original54Sent);
            if (post54_2a != null && postCccd2a != null) log("  post-BLOCK-C ATT 0x002A changed: " + (!ByteUtil.equalsBytes(postCccd2a, post54_2a)));
            if (final2a != null && postCccd2a != null) log("  final ATT 0x002A equals pre-control: " + ByteUtil.equalsBytes(postCccd2a, final2a));

            logFirstInteresting(prefix + " BLOCK A baseline", blockA_baseline);
            logFirstInteresting(prefix + " BLOCK B refresh-only", blockB_refreshOnly);
            logFirstInteresting(prefix + " BLOCK C original-0x0054", blockC_original54);
            logFirstInteresting(prefix + " BLOCK D refresh-after-0x0054", blockD_post54Refresh);

            boolean a52 = blockA_baseline.saw52();
            boolean b52 = blockB_refreshOnly.saw52();
            boolean c52 = blockC_original54.saw52();
            boolean d52 = blockD_post54Refresh.saw52();
            boolean c53 = blockC_original54.saw53();
            boolean d53 = blockD_post54Refresh.saw53();
            if (!a52 && !b52 && c52) {
                log(prefix + " interpretation: strong signal: 0x0052 appeared after the exact-original 0x0054 block but not in baseline/refresh-only. Treat 0x0054 as parsed/accepted and focus next on decoding 0x0052 timing/status.");
            } else if (!a52 && !b52 && !c52 && d52) {
                log(prefix + " interpretation: 0x0052 appeared only after the post-0x0054 refresh. That suggests a delayed 0x0054 status or a refresh-after-0x0054 side effect; next isolate with longer C wait vs D refresh.");
            } else if (a52 || b52) {
                log(prefix + " interpretation: 0x0052 is not specific to original 0x0054 in this run because it appeared in baseline and/or refresh-only.");
            } else if (c53 || d53) {
                log(prefix + " interpretation: no 0x0052 appeared, but 0x0053 replay did appear after original 0x0054/refresh. Compare replay payloads against selected current-session payload above.");
            } else {
                log(prefix + " interpretation: no 0x0052/0x0053/0x0055 control signal appeared in any isolated block. v27's delayed 0x0052 was likely timing noise or needs a different trigger.");
            }
            return true;
        } catch (Exception e) {
            log(prefix + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            closeQuietly(att);
            closeQuietly(aacp);
            log(prefix + " sockets closed.");
        }
    }

    private PacketRecord chooseCurrentSession53(AacpStreamSummary pre, AacpStreamSummary afterCccd, AacpStreamSummary afterReads) {
        if (afterReads != null && afterReads.first53 != null) return afterReads.first53;
        if (afterCccd != null && afterCccd.first53 != null) return afterCccd.first53;
        if (pre != null && pre.first53 != null) return pre.first53;
        return null;
    }

    private String chooseCurrentSession53Label(AacpStreamSummary pre, AacpStreamSummary afterCccd, AacpStreamSummary afterReads) {
        if (afterReads != null && afterReads.first53 != null) return "post-CCCD final-read/deep-drain 0x0053";
        if (afterCccd != null && afterCccd.first53 != null) return "direct post-CCCD 0x0053";
        if (pre != null && pre.first53 != null) return "early current-session 0x0053";
        return "none";
    }

    private boolean validate53ForOriginal54(byte[] payload) {
        if (payload == null) return false;
        int declared = payload.length >= 2 ? ByteUtil.u16le(payload, 0) : -1;
        log("v28 selected AACP 0x0053 original-0x0054 validation: len=" + payload.length + ", declared/body-ish=" + declared + ", float summary=" + ByteUtil.floatSummary(payload));
        if (payload.length < 6) {
            log("v28 original-0x0054 guard: refusing because payload is too short.");
            return false;
        }
        if (declared != payload.length - 2) {
            log("v28 original-0x0054 guard: refusing because declared/body-ish length " + declared + " does not equal payloadLen-2 " + (payload.length - 2) + ".");
            return false;
        }
        if (ByteUtil.u8(payload[2]) != 0x02 || ByteUtil.u8(payload[3]) != 0x00 || ByteUtil.u8(payload[4]) != 0x02 || ByteUtil.u8(payload[5]) != 0x02) {
            log("v28 original-0x0054 guard: refusing because prefix bytes [2..5] are not observed 02 00 02 02. Prefix="
                    + ByteUtil.hex(ByteUtil.copyOfRange(payload, 2, Math.min(payload.length, 6))));
            return false;
        }
        return true;
    }

    private byte[] aacp54FullPayload(byte[] payload) {
        return ByteUtil.concat(ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, 0x54, 0x00), payload == null ? new byte[0] : payload);
    }

    private void readImmediateRelative(BluetoothSocket socket, String label, long stimulusMs, String stimulusLabel, AacpStreamSummary collector) throws IOException {
        byte[] p = readOne(socket.getInputStream(), 2200);
        if (p != null) {
            logPacket(label + rel(stimulusMs, stimulusLabel), p);
            if (collector != null) collector.add(new PacketRecord(p, label));
        } else {
            log(label + rel(stimulusMs, stimulusLabel) + ": no packet.");
        }
    }

    private void drainAacpRelative(BluetoothSocket socket, String label, long stimulusMs, String stimulusLabel, long perReadMs, long maxTotalMs, int maxPackets, AacpStreamSummary collector) throws IOException {
        InputStream in = socket.getInputStream();
        long start = SystemClock.elapsedRealtime();
        int count = 0;
        while (count < maxPackets && SystemClock.elapsedRealtime() - start < maxTotalMs) {
            byte[] p = readOne(in, perReadMs);
            if (p == null) continue;
            count++;
            logPacket("AACP drain " + label + " packet " + count + rel(stimulusMs, stimulusLabel), p);
            if (collector != null) collector.add(new PacketRecord(p, label));
        }
        if (count == 0) log("AACP drain " + label + rel(stimulusMs, stimulusLabel) + ": no packets.");
    }

    private String rel(long stimulusMs, String stimulusLabel) {
        long delta = SystemClock.elapsedRealtime() - stimulusMs;
        return " (+" + delta + " ms since " + stimulusLabel + ")";
    }

    private void logBlockResult(String label, AacpStreamSummary s, byte[] selected53Payload) {
        byte[] first53 = s.first53 == null ? null : s.first53.payload();
        log("  " + label + ": packets=" + s.packets.size()
                + ", saw0x52=" + s.saw52()
                + ", saw0x53=" + s.saw53()
                + ", saw0x55=" + s.saw55()
                + ", commands=" + s.commandCountString());
        if (first53 != null && selected53Payload != null) {
            log("  " + label + ": first 0x0053 equals selected current-session 0x0053 payload: " + ByteUtil.equalsBytes(first53, selected53Payload));
        }
    }

    private void logFirstInteresting(String label, AacpStreamSummary s) {
        if (s.first52 != null) logPacketDetails(label + " first 0x0052", s.first52.packet);
        if (s.first53 != null) logPacketDetails(label + " first 0x0053", s.first53.packet);
        if (s.first55 != null) logPacketDetails(label + " first 0x0055", s.first55.packet);
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

        byte[] notify = notificationRequestAll();
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

    private byte[] notificationRequestAll() {
        return ByteUtil.concat(ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00), ByteUtil.bytes(0xFF, 0xFF, 0xFF, 0xFF));
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
