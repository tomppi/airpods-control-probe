package com.airpodsprobe.v29matrix;

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

final class AacpV29MatrixProbe {
    private static final int AACP_PSM = 4097;
    private static final int ATT_PSM = 31;

    private static final int HANDLE_SIBLING_21 = 0x0021;
    private static final int HANDLE_SIBLING_24 = 0x0024;
    private static final int HANDLE_HEARING_CONFIG = 0x002A;
    private static final int HANDLE_CCCD_21 = 0x0022;

    private static final float Q8_STEP = 1.0f / 256.0f;
    private static final long MATRIX_DRAIN_MS = 18000;

    private static final byte[] AACP_HANDSHAKE = ByteUtil.bytes(
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    );

    private final BluetoothDevice device;
    private final LogSink log;

    AacpV29MatrixProbe(BluetoothDevice device, LogSink log) {
        this.device = device;
        this.log = log;
    }

    void run() {
        log("=== AirPods AACP v29 0x0054 validation-matrix probe started ===");
        log("Device: " + safeName(device) + " / " + device.getAddress());
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.");
        log("It does not install or replace the Xposed module.");
        log("--- v29 pure AACP 0x0054 acceptance-oracle matrix ---");
        log("v28 proved exact-original 0x0053-as-0x0054 produces delayed AACP 0x0052, while baseline and refresh-only do not.");
        log("v29 therefore uses 0x0052 as an acceptance oracle for a small matrix of full-payload 0x0054 shapes.");
        log("No ATT 0x002A writes are performed. No AACP 0x0052, 0x0053, or 0x0055 candidates are sent. 0x0052/0x0053/0x0055 are only observed as responses.");
        log("Matrix: positive original, float[0]+Q8, restore, float[15]+Q8, restore, float[31]+Q8, restore, all32+Q8 uniform, restore.");

        boolean completed = false;
        final int attempts = 2;
        for (int i = 1; i <= attempts; i++) {
            completed = runMatrixAttempt(i, attempts);
            if (completed) break;
            if (i < attempts) {
                log("v29 matrix: attempt " + i + " did not reach the guarded matrix. Sleeping briefly, then retrying once.");
                sleep(1300);
            }
        }
        if (!completed) {
            log("v29 final result: no guarded current-session 0x0053 payload was available, or the payload failed mutation guards, so the 0x0054 matrix was not sent.");
        }
        log("=== Probe finished ===");
    }

    private boolean runMatrixAttempt(int attempt, int totalAttempts) {
        BluetoothSocket aacp = null;
        BluetoothSocket att = null;
        AacpStreamSummary preCccd = new AacpStreamSummary();
        AacpStreamSummary afterCccd = new AacpStreamSummary();
        AacpStreamSummary afterPostCccdReads = new AacpStreamSummary();

        byte[] before2a = null;
        byte[] postCccd2a = null;
        byte[] final2a = null;

        String prefix = "v29 attempt " + attempt + "/" + totalAttempts;
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

            log(prefix + ": continuing with v20-style post-CCCD ATT reads before any v29 matrix 0x0054.");
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
                log(prefix + " abort: no current-session AACP 0x0053 vector was captured. The 0x0054 matrix will not be sent.");
                return false;
            }
            byte[] original53 = context53.payload();
            logPacketDetails(prefix + " selected " + context53Label + " 0x0054 matrix source", context53.packet);
            if (!validate53ForMatrix(original53)) {
                log(prefix + " abort: selected AACP 0x0053 payload failed the conservative v29 matrix guard. No 0x0054 matrix frames will be sent.");
                return false;
            }
            log(prefix + " selected original 0x0053 payload length=" + original53.length + ", bytes=" + ByteUtil.hex(original53));

            byte[] one0 = mutateOneFloat(original53, 0, Q8_STEP, prefix + " TEST float[0] Q8 canary");
            byte[] one15 = mutateOneFloat(original53, 15, Q8_STEP, prefix + " TEST float[15] Q8 canary");
            byte[] one31 = mutateOneFloat(original53, 31, Q8_STEP, prefix + " TEST float[31] Q8 canary");
            byte[] all32 = mutateAllFloatsSameDirection(original53, Q8_STEP, prefix + " TEST all32 uniform Q8 canary");
            if (one0 == null || one15 == null || one31 == null || all32 == null) {
                log(prefix + " abort: at least one planned canary payload failed mutation guards. No partial matrix will be sent.");
                return false;
            }

            AacpStreamSummary positiveOriginal = run54MatrixBlock(aacp, prefix, "POSITIVE CONTROL exact original 0x0053 via 0x0054", original53, original53, MATRIX_DRAIN_MS);
            AacpStreamSummary test0 = run54MatrixBlock(aacp, prefix, "TEST 1 float[0] +Q8 via 0x0054", one0, original53, MATRIX_DRAIN_MS);
            AacpStreamSummary restore0 = run54MatrixBlock(aacp, prefix, "RESTORE after TEST 1 exact original via 0x0054", original53, original53, MATRIX_DRAIN_MS);
            AacpStreamSummary test15 = run54MatrixBlock(aacp, prefix, "TEST 2 float[15] +Q8 via 0x0054", one15, original53, MATRIX_DRAIN_MS);
            AacpStreamSummary restore15 = run54MatrixBlock(aacp, prefix, "RESTORE after TEST 2 exact original via 0x0054", original53, original53, MATRIX_DRAIN_MS);
            AacpStreamSummary test31 = run54MatrixBlock(aacp, prefix, "TEST 3 float[31] +Q8 via 0x0054", one31, original53, MATRIX_DRAIN_MS);
            AacpStreamSummary restore31 = run54MatrixBlock(aacp, prefix, "RESTORE after TEST 3 exact original via 0x0054", original53, original53, MATRIX_DRAIN_MS);
            AacpStreamSummary testAll = run54MatrixBlock(aacp, prefix, "TEST 4 all 32 floats +Q8 uniform via 0x0054", all32, original53, MATRIX_DRAIN_MS);
            AacpStreamSummary restoreAll = run54MatrixBlock(aacp, prefix, "FINAL RESTORE exact original via 0x0054", original53, original53, MATRIX_DRAIN_MS);

            drainAtt(att, prefix + " ATT after v29 matrix", 40, 2200);
            final2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " final 0x002A read-only context after v29 matrix");
            if (final2a != null) {
                log(prefix + " final 0x002A value: " + ByteUtil.hex(final2a));
                decode2a(prefix + " final 0x002A decode", final2a);
                if (postCccd2a != null) log(prefix + " final 0x002A equals pre-matrix 0x002A: " + ByteUtil.equalsBytes(postCccd2a, final2a));
            }
            drainAacp(aacp, prefix + " final deep drain after v29 matrix and final ATT read", 160, 6000, 160, new AacpStreamSummary());

            byte[] positive52 = positiveOriginal.first52 == null ? null : positiveOriginal.first52.payload();
            log(prefix + " result matrix:");
            logMatrixResult("POSITIVE original", positiveOriginal, positive52, original53);
            logMatrixResult("TEST 1 float[0]+Q8", test0, positive52, original53);
            logMatrixResult("RESTORE after TEST 1", restore0, positive52, original53);
            logMatrixResult("TEST 2 float[15]+Q8", test15, positive52, original53);
            logMatrixResult("RESTORE after TEST 2", restore15, positive52, original53);
            logMatrixResult("TEST 3 float[31]+Q8", test31, positive52, original53);
            logMatrixResult("RESTORE after TEST 3", restore31, positive52, original53);
            logMatrixResult("TEST 4 all32+Q8 uniform", testAll, positive52, original53);
            logMatrixResult("FINAL RESTORE", restoreAll, positive52, original53);
            if (final2a != null && postCccd2a != null) log("  final ATT 0x002A equals pre-matrix: " + ByteUtil.equalsBytes(postCccd2a, final2a));

            interpretMatrix(prefix, positiveOriginal, test0, restore0, test15, restore15, test31, restore31, testAll, restoreAll);
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

    private AacpStreamSummary run54MatrixBlock(BluetoothSocket aacp, String prefix, String blockLabel, byte[] payload, byte[] originalPayload, long waitMs) throws IOException {
        AacpStreamSummary summary = new AacpStreamSummary();
        log("--- " + prefix + " MATRIX BLOCK: " + blockLabel + " ---");
        log(prefix + " " + blockLabel + " payload length=" + payload.length + ", equals original=" + ByteUtil.equalsBytes(payload, originalPayload));
        log(prefix + " " + blockLabel + " payload float summary: " + ByteUtil.floatSummary(payload));
        byte[] frame = aacp54FullPayload(payload);
        log(prefix + " " + blockLabel + " 0x0054 frame bytes: " + ByteUtil.hex(frame));
        long stimulus = SystemClock.elapsedRealtime();
        sendAacp(aacp.getOutputStream(), frame, prefix + " " + blockLabel);
        readImmediateRelative(aacp, prefix + " immediate packet after " + blockLabel, stimulus, blockLabel, summary);
        drainAacpRelative(aacp, prefix + " after " + blockLabel, stimulus, blockLabel, 160, waitMs, 360, summary);
        summarizeStream(prefix + " " + blockLabel + " stream", summary);
        logFirstInteresting(prefix + " " + blockLabel, summary);
        if (summary.first53 != null) {
            log(prefix + " " + blockLabel + " first 0x0053 equals original selected 0x0053 payload: "
                    + ByteUtil.equalsBytes(summary.first53.payload(), originalPayload));
        }
        return summary;
    }

    private void interpretMatrix(String prefix,
                                 AacpStreamSummary positiveOriginal,
                                 AacpStreamSummary test0,
                                 AacpStreamSummary restore0,
                                 AacpStreamSummary test15,
                                 AacpStreamSummary restore15,
                                 AacpStreamSummary test31,
                                 AacpStreamSummary restore31,
                                 AacpStreamSummary testAll,
                                 AacpStreamSummary restoreAll) {
        boolean positive = positiveOriginal.saw52();
        boolean anySingle = test0.saw52() || test15.saw52() || test31.saw52();
        boolean allUniform = testAll.saw52();
        boolean allRestores = restore0.saw52() && restore15.saw52() && restore31.saw52() && restoreAll.saw52();
        log(prefix + " interpretation inputs: positiveOriginalSaw52=" + positive
                + ", anySingleSlotMutationSaw52=" + anySingle
                + ", all32UniformMutationSaw52=" + allUniform
                + ", allRestoresSaw52=" + allRestores + ".");
        if (!positive) {
            log(prefix + " interpretation: positive original 0x0054 did not produce 0x0052 in this run. Treat the matrix as inconclusive and rerun before changing hypotheses.");
        } else if (!allRestores) {
            log(prefix + " interpretation: positive original worked, but at least one original restore did not produce 0x0052. The oracle/state may have cooled down, rate-limited, or become timing-sensitive; repeat with longer restore waits before trusting mutation negatives.");
        } else if (!anySingle && allUniform) {
            log(prefix + " interpretation: single-slot mutations failed but all-32 uniform mutation produced 0x0052. That strongly suggests a vector-level constraint such as uniformity/shape consistency rather than a completely read-only setter.");
        } else if (anySingle) {
            log(prefix + " interpretation: at least one single-slot mutation produced 0x0052. Compare which slot(s) accepted; that slot class is likely writable/validated.");
        } else if (!anySingle && !allUniform) {
            log(prefix + " interpretation: exact-original 0x0054 and restores are accepted, but every Q8 mutation shape was silent. That points to strict validation, missing authorization/commit context, or 0x0054 being an integrity-checked setter rather than a free profile writer.");
        } else {
            log(prefix + " interpretation: mixed result; inspect per-block first 0x0052 payloads and timings above.");
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

    private boolean validate53ForMatrix(byte[] payload) {
        if (payload == null) return false;
        int declared = payload.length >= 2 ? ByteUtil.u16le(payload, 0) : -1;
        log("v29 selected AACP 0x0053 matrix validation: len=" + payload.length + ", declared/body-ish=" + declared + ", float summary=" + ByteUtil.floatSummary(payload));
        if (payload.length < 10) {
            log("v29 matrix guard: refusing because payload is too short.");
            return false;
        }
        if (declared != payload.length - 2) {
            log("v29 matrix guard: refusing because declared/body-ish length " + declared + " does not equal payloadLen-2 " + (payload.length - 2) + ".");
            return false;
        }
        if (ByteUtil.u8(payload[2]) != 0x02 || ByteUtil.u8(payload[3]) != 0x00 || ByteUtil.u8(payload[4]) != 0x02 || ByteUtil.u8(payload[5]) != 0x02) {
            log("v29 matrix guard: refusing because prefix bytes [2..5] are not observed 02 00 02 02. Prefix="
                    + ByteUtil.hex(ByteUtil.copyOfRange(payload, 2, Math.min(payload.length, 6))));
            return false;
        }
        if (((payload.length - 6) % 4) != 0) {
            log("v29 matrix guard: refusing because bytes after prefix are not an integer float32 count.");
            return false;
        }
        int count = floatCount53(payload);
        if (count != 32) {
            log("v29 matrix guard: refusing because float32 count is " + count + ", expected observed count 32.");
            return false;
        }
        for (int i = 0; i < count; i++) {
            float v = floatAt(payload, i);
            if (!Float.isFinite(v) || v < 0.0f || v > 1.0f) {
                log("v29 matrix guard: refusing because float[" + i + "]=" + v + " is not finite in [0,1].");
                return false;
            }
        }
        return true;
    }

    private byte[] mutateOneFloat(byte[] original, int index, float step, String label) {
        if (!validate53ForMatrix(original)) return null;
        int count = floatCount53(original);
        if (index < 0 || index >= count) {
            log(label + ": refusing because index " + index + " is outside float count " + count + ".");
            return null;
        }
        float old = floatAt(original, index);
        float delta = chooseDelta(old, step);
        if (Float.isNaN(delta)) {
            log(label + ": refusing because +/-" + step + " would leave [0,1] for original value " + old + ".");
            return null;
        }
        byte[] out = ByteUtil.copyOfRange(original, 0, original.length);
        int off = floatOffset(index);
        float neu = old + delta;
        byte[] before = ByteUtil.copyOfRange(out, off, off + 4);
        writeF32le(out, off, neu);
        byte[] after = ByteUtil.copyOfRange(out, off, off + 4);
        log(label + String.format(Locale.US,
                ": changes only float[%d] at payload offset %d: %.8f (%s) -> %.8f (%s), delta=%.8f",
                index, off, old, ByteUtil.hex(before), neu, ByteUtil.hex(after), delta));
        log(label + " payload bytes=" + ByteUtil.hex(out));
        return out;
    }

    private byte[] mutateAllFloatsSameDirection(byte[] original, float step, String label) {
        if (!validate53ForMatrix(original)) return null;
        int count = floatCount53(original);
        boolean canPlus = true;
        boolean canMinus = true;
        for (int i = 0; i < count; i++) {
            float v = floatAt(original, i);
            if (v + step > 1.0f) canPlus = false;
            if (v - step < 0.0f) canMinus = false;
        }
        float delta;
        if (canPlus) delta = step;
        else if (canMinus) delta = -step;
        else {
            log(label + ": refusing because neither +Q8 nor -Q8 is valid for every float.");
            return null;
        }
        byte[] out = ByteUtil.copyOfRange(original, 0, original.length);
        for (int i = 0; i < count; i++) {
            float old = floatAt(original, i);
            writeF32le(out, floatOffset(i), old + delta);
        }
        log(label + String.format(Locale.US,
                ": changes all %d floats by the same delta %.8f; first %.8f -> %.8f, last %.8f -> %.8f",
                count, delta, floatAt(original, 0), floatAt(out, 0), floatAt(original, count - 1), floatAt(out, count - 1)));
        log(label + " payload bytes=" + ByteUtil.hex(out));
        return out;
    }

    private float chooseDelta(float old, float step) {
        if (old + step <= 1.0f) return step;
        if (old - step >= 0.0f) return -step;
        return Float.NaN;
    }

    private int floatCount53(byte[] payload) {
        return payload == null || payload.length < 6 ? 0 : (payload.length - 6) / 4;
    }

    private int floatOffset(int index) {
        return 6 + index * 4;
    }

    private float floatAt(byte[] payload, int index) {
        return ByteUtil.f32le(payload, floatOffset(index));
    }

    private void writeF32le(byte[] b, int off, float value) {
        int bits = Float.floatToIntBits(value);
        b[off] = (byte) (bits & 0xFF);
        b[off + 1] = (byte) ((bits >> 8) & 0xFF);
        b[off + 2] = (byte) ((bits >> 16) & 0xFF);
        b[off + 3] = (byte) ((bits >> 24) & 0xFF);
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

    private void logMatrixResult(String label, AacpStreamSummary s, byte[] positive52Payload, byte[] selected53Payload) {
        log("  " + label + ": packets=" + s.packets.size()
                + ", saw0x52=" + s.saw52()
                + ", saw0x53=" + s.saw53()
                + ", saw0x55=" + s.saw55()
                + ", commands=" + s.commandCountString());
        if (s.first52 != null) {
            byte[] p52 = s.first52.payload();
            log("  " + label + ": first 0x0052 payload=" + ByteUtil.hex(p52));
            if (positive52Payload != null) log("  " + label + ": first 0x0052 equals positive-control first 0x0052: " + ByteUtil.equalsBytes(p52, positive52Payload));
        }
        if (s.first53 != null && selected53Payload != null) {
            log("  " + label + ": first 0x0053 equals selected current-session 0x0053 payload: " + ByteUtil.equalsBytes(s.first53.payload(), selected53Payload));
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
