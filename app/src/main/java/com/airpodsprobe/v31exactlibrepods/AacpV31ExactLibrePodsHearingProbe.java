package com.airpodsprobe.v31exactlibrepods;

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

final class AacpV31ExactLibrePodsHearingProbe {
    private static final int AACP_PSM = 4097;
    private static final int ATT_PSM = 31;

    private static final int HANDLE_SIBLING_21 = 0x0021;
    private static final int HANDLE_SIBLING_24 = 0x0024;
    private static final int HANDLE_HEARING_CONFIG = 0x002A;
    private static final int HANDLE_CCCD_21 = 0x0022;
    private static final int HANDLE_CCCD_HEARING_CONFIG = 0x002B;

    private static final int CC_HEARING_AID = 0x2C;
    private static final int CC_HEARING_ASSIST_CONFIG = 0x33;
    private static final int CC_PPE_TOGGLE_CONFIG = 0x37;
    private static final int CC_PPE_CAP_LEVEL_CONFIG = 0x38;

    private static final float DBHL_STEP = 1.0f;
    private static final int OFF_LEFT_EQ_250HZ = 4;
    private static final long CONTROL_DRAIN_MS = 9000;
    private static final long ATT_POST_WRITE_DRAIN_MS = 9000;
    private static final long AACP54_DRAIN_MS = 12000;

    private static final byte[] AACP_HANDSHAKE = ByteUtil.bytes(
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    );

    private final BluetoothDevice device;
    private final LogSink log;

    AacpV31ExactLibrePodsHearingProbe(BluetoothDevice device, LogSink log) {
        this.device = device;
        this.log = log;
    }

    void run() {
        log("=== AirPods AACP v31 exact-LibrePods hearing settings probe started ===");
        log("Device: " + safeName(device) + " / " + device.getAddress());
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.");
        log("It does not install or replace the Xposed module.");
        log("--- v31 pivot: follow the exact LibrePods ATT hearing-aid settings path, not only the AACP toggle ---");
        log("LibrePods' AACP control-command enum names HEARING_AID as id 0x2C. Its boolean sender maps true to data byte 0x01 and false to 0x02.");
        log("LibrePods' hearing-aid settings writer reads ATT 0x002A, changes payload byte[2] from the read-side 0x60 shape to 0x64 before writing, then writes the updated settings back to ATT 0x002A.");
        log("v31 adds the missing LibrePods ATT step: enable the HEARING_AID CCCD at handle 0x002B before reading/writing ATT 0x002A.");
        log("v31 also changes a real LibrePods field, left_eq[0] at offset 4, by +1.0 dBHL instead of poking the own_voice field.");
        log("The semantic canary is reversible: left_eq[0] +1.0 dBHL, then restore original values, both with byte[2]=0x64.");
        log("v31 sends no AACP 0x0052, 0x0053, or 0x0055 candidates. They are observed only as responses.");

        boolean completed = false;
        final int attempts = 2;
        for (int i = 1; i <= attempts; i++) {
            completed = runHearingGateAttempt(i, attempts);
            if (completed) break;
            if (i < attempts) {
                log("v31 hearing gate: attempt " + i + " did not reach the guarded test. Sleeping briefly, then retrying once.");
                sleep(1300);
            }
        }
        if (!completed) {
            log("v31 final result: no guarded current-session 0x002A context / 0x0053 context was available, so the gated test was not sent.");
        }
        log("=== Probe finished ===");
    }

    private boolean runHearingGateAttempt(int attempt, int totalAttempts) {
        BluetoothSocket aacp = null;
        BluetoothSocket att = null;
        AacpStreamSummary preCccd = new AacpStreamSummary();
        AacpStreamSummary afterCccd = new AacpStreamSummary();
        AacpStreamSummary afterPostCccdReads = new AacpStreamSummary();
        byte[] before2a = null;
        byte[] postCccd2a = null;
        byte[] postEnable2a = null;
        byte[] postCanary2a = null;
        byte[] postRestore2a = null;

        String prefix = "v31 attempt " + attempt + "/" + totalAttempts;
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

            log(prefix + ": connecting ATT PSM " + ATT_PSM + " for read/write settings probe.");
            att = connectL2cap(device, ATT_PSM, false, prefix + " ATT");
            if (att == null) {
                log(prefix + " abort: failed to connect ATT PSM " + ATT_PSM + ".");
                return false;
            }

            drainAtt(att, prefix + " ATT stale after open", 24, 1300);
            drainAacp(aacp, prefix + " after ATT open", 140, 4200, 160, preCccd);

            attRead(att, HANDLE_SIBLING_21, prefix + " pre-CCCD sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, prefix + " pre-CCCD sibling 0x0024");
            before2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " pre-CCCD 0x002A context");
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

            log(prefix + ": enabling exact LibrePods HEARING_AID CCCD: ATT handle 0x002B = 01 00.");
            boolean hearingCccdOk = attWriteRequest(att, HANDLE_CCCD_HEARING_CONFIG, ByteUtil.bytes(0x01, 0x00), prefix + " enable LibrePods HEARING_AID CCCD 0x002B");
            log(prefix + ": LibrePods HEARING_AID CCCD 0x002B write result: " + hearingCccdOk);
            drainAtt(att, prefix + " ATT after LibrePods HEARING_AID CCCD 0x002B", 60, 2600);

            log(prefix + ": continuing with exact-LibrePods post-0x002B ATT reads before the hearing-aid toggle.");
            attRead(att, HANDLE_SIBLING_21, prefix + " post-CCCD sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, prefix + " post-CCCD sibling 0x0024");
            postCccd2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " post-CCCD 0x002A pre-toggle context");
            if (postCccd2a != null) {
                log(prefix + " post-CCCD 0x002A value: " + ByteUtil.hex(postCccd2a));
                decode2a(prefix + " post-CCCD 0x002A decode", postCccd2a);
                decodeLibrePods2aFields(prefix + " post-0x002B 0x002A LibrePods field decode", postCccd2a);
                if (before2a != null) log(prefix + " post-CCCD 0x002A equals pre-CCCD: " + ByteUtil.equalsBytes(before2a, postCccd2a));
            }
            drainAacp(aacp, prefix + " final capture deep drain after post-CCCD reads", 140, 14500, 320, afterPostCccdReads);

            summarizeStream(prefix + " pre-CCCD stream", preCccd);
            summarizeStream(prefix + " direct post-CCCD stream", afterCccd);
            summarizeStream(prefix + " post-CCCD-read deep stream", afterPostCccdReads);
            logKnownHearingControl(prefix + " observed pre-toggle", preCccd, afterCccd, afterPostCccdReads);

            if (!validate2aForLibrePodsWrite(postCccd2a)) {
                log(prefix + " abort: post-CCCD ATT 0x002A does not match the guarded shape for the LibrePods-style settings write.");
                return false;
            }

            PacketRecord context53 = chooseCurrentSession53(preCccd, afterCccd, afterPostCccdReads);
            byte[] original53 = context53 == null ? null : context53.payload();
            if (context53 != null) {
                logPacketDetails(prefix + " optional selected current-session 0x0053 context", context53.packet);
            } else {
                log(prefix + ": no 0x0053 context captured. That is OK for the ATT settings path; v31 will skip the optional 0x0054 check.");
            }

            int priorHearingAidState = firstDataByteForControl(preCccd, afterCccd, afterPostCccdReads, CC_HEARING_AID);
            log(prefix + String.format(Locale.US,
                    " observed HEARING_AID control id 0x%02X data1 before toggle: %s",
                    CC_HEARING_AID, priorHearingAidState < 0 ? "<not observed>" : String.format(Locale.US, "0x%02X", priorHearingAidState)));

            AacpStreamSummary enableSummary = runControlBlock(aacp, prefix, "BLOCK A LibrePods HEARING_AID ON", CC_HEARING_AID, 0x01, CONTROL_DRAIN_MS);
            drainAtt(att, prefix + " ATT after HEARING_AID ON", 40, 2200);
            postEnable2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " post-HEARING_AID-ON 0x002A context");
            if (postEnable2a != null) {
                log(prefix + " post-HEARING_AID-ON 0x002A value: " + ByteUtil.hex(postEnable2a));
                decode2a(prefix + " post-HEARING_AID-ON 0x002A decode", postEnable2a);
                decodeLibrePods2aFields(prefix + " post-HEARING_AID-ON 0x002A LibrePods field decode", postEnable2a);
                log(prefix + " post-HEARING_AID-ON 0x002A equals pre-toggle 0x002A: " + ByteUtil.equalsBytes(postCccd2a, postEnable2a));
            }

            byte[] source2a = postEnable2a != null ? postEnable2a : postCccd2a;
            byte[] canary2a = makeLibrePodsStyle2aWritePayload(source2a, true, prefix + " ATT 0x002A canary");
            byte[] restore2a = makeLibrePodsStyle2aWritePayload(source2a, false, prefix + " ATT 0x002A restore");
            if (canary2a == null || restore2a == null) {
                log(prefix + " abort: could not build guarded LibrePods-style 0x002A canary/restore payloads.");
                return false;
            }

            log("--- " + prefix + " BLOCK B: ATT 0x002A LibrePods-style byte[2]=0x64 dBHL canary after HEARING_AID ON ---");
            boolean canaryOk = attWriteRequest(att, HANDLE_HEARING_CONFIG, canary2a, prefix + " LibrePods-style 0x002A dBHL canary write");
            log(prefix + " BLOCK B: canary ATT 0x002A write accepted: " + canaryOk);
            drainAtt(att, prefix + " ATT after gated 0x002A canary write", 40, 2200);
            AacpStreamSummary afterCanaryAacp = new AacpStreamSummary();
            drainAacp(aacp, prefix + " AACP after gated 0x002A canary write", 160, ATT_POST_WRITE_DRAIN_MS, 160, afterCanaryAacp);
            postCanary2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " post-gated-canary 0x002A readback");
            if (postCanary2a != null) {
                log(prefix + " post-gated-canary 0x002A value: " + ByteUtil.hex(postCanary2a));
                decode2a(prefix + " post-gated-canary 0x002A decode", postCanary2a);
                decodeLibrePods2aFields(prefix + " post-gated-canary 0x002A LibrePods field decode", postCanary2a);
                log2aSemanticComparisons(prefix + " post-gated-canary", source2a, canary2a, postCanary2a);
            }
            summarizeStream(prefix + " AACP after gated 0x002A canary", afterCanaryAacp);

            log("--- " + prefix + " BLOCK C: restore original semantic 0x002A values using LibrePods-style byte[2]=0x64 write shape ---");
            boolean restoreOk = attWriteRequest(att, HANDLE_HEARING_CONFIG, restore2a, prefix + " LibrePods-style 0x002A restore write");
            log(prefix + " BLOCK C: restore ATT 0x002A write accepted: " + restoreOk);
            drainAtt(att, prefix + " ATT after gated 0x002A restore write", 40, 2200);
            AacpStreamSummary afterRestoreAacp = new AacpStreamSummary();
            drainAacp(aacp, prefix + " AACP after gated 0x002A restore write", 160, ATT_POST_WRITE_DRAIN_MS, 160, afterRestoreAacp);
            postRestore2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " post-gated-restore 0x002A readback");
            if (postRestore2a != null) {
                log(prefix + " post-gated-restore 0x002A value: " + ByteUtil.hex(postRestore2a));
                decode2a(prefix + " post-gated-restore 0x002A decode", postRestore2a);
                decodeLibrePods2aFields(prefix + " post-gated-restore 0x002A LibrePods field decode", postRestore2a);
                log2aSemanticComparisons(prefix + " post-gated-restore", source2a, restore2a, postRestore2a);
            }
            summarizeStream(prefix + " AACP after gated 0x002A restore", afterRestoreAacp);

            AacpStreamSummary optional54Original = null;
            if (original53 != null && validate53ForOptional54(original53)) {
                optional54Original = run54Block(aacp, prefix, "BLOCK D optional AACP 0x0054 exact original after HEARING_AID ON", original53, original53, AACP54_DRAIN_MS);
            }

            AacpStreamSummary offSummary = null;
            if (priorHearingAidState == 0x02) {
                offSummary = runControlBlock(aacp, prefix, "BLOCK E restore prior HEARING_AID OFF", CC_HEARING_AID, 0x02, CONTROL_DRAIN_MS);
            } else {
                log(prefix + " BLOCK E: not toggling HEARING_AID off because prior state was not observed as disabled. Leaving it as the app/user had it or as enabled for test continuity.");
            }

            log(prefix + " result summary:");
            log("  HEARING_AID ON block saw0x52=" + enableSummary.saw52() + ", saw0x53=" + enableSummary.saw53() + ", saw0x55=" + enableSummary.saw55() + ", commands=" + enableSummary.commandCountString());
            log("  gated ATT 0x002A canary write accepted=" + canaryOk);
            log("  gated ATT 0x002A restore write accepted=" + restoreOk);
            if (postCanary2a != null) {
                log("  post-canary left_eq[0]=" + ByteUtil.f32le(postCanary2a, OFF_LEFT_EQ_250HZ)
                        + ", equals original read=" + ByteUtil.equalsBytes(postCanary2a, source2a)
                        + ", left_eq[0] equals canary=" + nearlyEqual(ByteUtil.f32le(postCanary2a, OFF_LEFT_EQ_250HZ), ByteUtil.f32le(canary2a, OFF_LEFT_EQ_250HZ)));
            }
            if (postRestore2a != null) {
                log("  post-restore left_eq[0]=" + ByteUtil.f32le(postRestore2a, OFF_LEFT_EQ_250HZ)
                        + ", equals source read=" + ByteUtil.equalsBytes(postRestore2a, source2a)
                        + ", left_eq[0] equals source=" + nearlyEqual(ByteUtil.f32le(postRestore2a, OFF_LEFT_EQ_250HZ), ByteUtil.f32le(source2a, OFF_LEFT_EQ_250HZ)));
            }
            if (optional54Original != null) {
                log("  optional post-toggle 0x0054 original saw0x52=" + optional54Original.saw52() + ", commands=" + optional54Original.commandCountString());
            }
            if (offSummary != null) {
                log("  HEARING_AID OFF restore block saw0x52=" + offSummary.saw52() + ", commands=" + offSummary.commandCountString());
            }

            interpretHearingGate(prefix, source2a, canary2a, postCanary2a, postRestore2a, enableSummary, afterCanaryAacp, optional54Original);
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

    private AacpStreamSummary runControlBlock(BluetoothSocket aacp, String prefix, String label, int id, int data1, long waitMs) throws IOException {
        AacpStreamSummary summary = new AacpStreamSummary();
        byte[] frame = aacpControlCommand(id, data1);
        log("--- " + prefix + " " + label + " ---");
        log(prefix + String.format(Locale.US, " %s sends AACP control 0x0009 id=0x%02X data=%02X 00 00 00", label, id, data1));
        long stimulus = SystemClock.elapsedRealtime();
        sendAacp(aacp.getOutputStream(), frame, prefix + " " + label);
        readImmediateRelative(aacp, prefix + " immediate packet after " + label, stimulus, label, summary);
        drainAacpRelative(aacp, prefix + " after " + label, stimulus, label, 160, waitMs, 160, summary);
        summarizeStream(prefix + " " + label + " stream", summary);
        logFirstInteresting(prefix + " " + label, summary);
        logKnownHearingControl(prefix + " " + label + " observed", summary);
        return summary;
    }

    private AacpStreamSummary run54Block(BluetoothSocket aacp, String prefix, String label, byte[] payload, byte[] original, long waitMs) throws IOException {
        AacpStreamSummary summary = new AacpStreamSummary();
        log("--- " + prefix + " " + label + " ---");
        log(prefix + " " + label + " payload length=" + payload.length + ", equals original=" + ByteUtil.equalsBytes(payload, original));
        log(prefix + " " + label + " payload float summary: " + ByteUtil.floatSummary(payload));
        byte[] frame = aacp54FullPayload(payload);
        long stimulus = SystemClock.elapsedRealtime();
        sendAacp(aacp.getOutputStream(), frame, prefix + " " + label);
        readImmediateRelative(aacp, prefix + " immediate packet after " + label, stimulus, label, summary);
        drainAacpRelative(aacp, prefix + " after " + label, stimulus, label, 160, waitMs, 160, summary);
        summarizeStream(prefix + " " + label + " stream", summary);
        logFirstInteresting(prefix + " " + label, summary);
        return summary;
    }

    private byte[] aacpControlCommand(int id, int data1) {
        return ByteUtil.bytes(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, id & 0xFF, data1 & 0xFF, 0x00, 0x00, 0x00);
    }

    private boolean validate2aForLibrePodsWrite(byte[] v) {
        if (v == null) return false;
        log("v31 selected ATT 0x002A settings validation: len=" + v.length + ", nonZero=" + ByteUtil.nonZero(v));
        decode2a("v31 selected ATT 0x002A settings source decode", v);
        if (v.length != 104) {
            log("v31 ATT 0x002A guard: refusing because length is " + v.length + ", expected observed 104.");
            return false;
        }
        if (ByteUtil.u8(v[0]) != 0x02) {
            log("v31 ATT 0x002A guard: refusing because byte[0] is not 0x02.");
            return false;
        }
        int declared = ByteUtil.u16le(v, 2);
        if (declared != 0x0060 && declared != 0x0064) {
            log("v31 ATT 0x002A guard: refusing because byte[2..3] is neither read-shape 0x0060 nor write-shape 0x0064. Observed=" + declared);
            return false;
        }
        if (v.length < OFF_LEFT_EQ_250HZ + 4) {
            log("v31 ATT 0x002A guard: refusing because left_eq[0] offset is unavailable.");
            return false;
        }
        float leftEq0 = ByteUtil.f32le(v, OFF_LEFT_EQ_250HZ);
        if (!Float.isFinite(leftEq0) || leftEq0 < -20.0f || leftEq0 > 130.0f) {
            log("v31 ATT 0x002A guard: refusing because left_eq[0] " + leftEq0 + " is outside a broad dBHL sanity range.");
            return false;
        }
        return true;
    }

    private byte[] makeLibrePodsStyle2aWritePayload(byte[] source, boolean canary, String label) {
        if (!validate2aForLibrePodsWrite(source)) return null;
        byte[] out = ByteUtil.copyOfRange(source, 0, source.length);
        int oldDeclared = ByteUtil.u16le(out, 2);
        out[2] = 0x64;
        out[3] = 0x00;
        int off = OFF_LEFT_EQ_250HZ;
        float oldLeftEq0 = ByteUtil.f32le(source, off);
        float target = oldLeftEq0;
        if (canary) {
            target = oldLeftEq0 + (oldLeftEq0 + DBHL_STEP <= 120.0f ? DBHL_STEP : -DBHL_STEP);
        }
        byte[] before = ByteUtil.copyOfRange(out, off, off + 4);
        writeF32le(out, off, target);
        byte[] after = ByteUtil.copyOfRange(out, off, off + 4);
        log(label + String.format(Locale.US,
                ": LibrePods-style write marker byte[2..3] %d -> %d, left_eq[0]@4 %.8f dBHL (%s) -> %.8f dBHL (%s), canary=%s",
                oldDeclared, ByteUtil.u16le(out, 2), oldLeftEq0, ByteUtil.hex(before), target, ByteUtil.hex(after), canary));
        log(label + " payload bytes=" + ByteUtil.hex(out));
        return out;
    }

    private void decodeLibrePods2aFields(String label, byte[] v) {
        if (v == null || v.length < 104) return;
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) left.append(", ");
            left.append(String.format(Locale.US, "%.2f", ByteUtil.f32le(v, 4 + i * 4)));
        }
        for (int i = 0; i < 8; i++) {
            if (i > 0) right.append(", ");
            right.append(String.format(Locale.US, "%.2f", ByteUtil.f32le(v, 52 + i * 4)));
        }
        log(label + ": left_eq[8]=" + left + "; left_amp=" + ByteUtil.f32le(v, 36)
                + ", left_tone=" + ByteUtil.f32le(v, 40)
                + ", left_conv=" + ByteUtil.f32le(v, 44)
                + ", left_anr=" + ByteUtil.f32le(v, 48));
        log(label + ": right_eq[8]=" + right + "; right_amp=" + ByteUtil.f32le(v, 84)
                + ", right_tone=" + ByteUtil.f32le(v, 88)
                + ", right_conv=" + ByteUtil.f32le(v, 92)
                + ", right_anr=" + ByteUtil.f32le(v, 96)
                + ", own_voice=" + ByteUtil.f32le(v, 100));
    }

    private void log2aSemanticComparisons(String label, byte[] sourceRead, byte[] writePayload, byte[] readback) {
        if (sourceRead == null || writePayload == null || readback == null) return;
        log(label + ": readback equals source read byte-for-byte: " + ByteUtil.equalsBytes(readback, sourceRead));
        log(label + ": readback equals write payload byte-for-byte: " + ByteUtil.equalsBytes(readback, writePayload));
        float sourceEq = ByteUtil.f32le(sourceRead, OFF_LEFT_EQ_250HZ);
        float writeEq = ByteUtil.f32le(writePayload, OFF_LEFT_EQ_250HZ);
        float readEq = ByteUtil.f32le(readback, OFF_LEFT_EQ_250HZ);
        log(label + String.format(Locale.US,
                ": left_eq[0] source=%.8f, writePayload=%.8f, readback=%.8f; readback==source=%s, readback==write=%s",
                sourceEq, writeEq, readEq,
                nearlyEqual(sourceEq, readEq),
                nearlyEqual(writeEq, readEq)));
    }

    private void interpretHearingGate(String prefix,
                                      byte[] source2a,
                                      byte[] canary2a,
                                      byte[] postCanary2a,
                                      byte[] postRestore2a,
                                      AacpStreamSummary enableSummary,
                                      AacpStreamSummary afterCanaryAacp,
                                      AacpStreamSummary optional54Original) {
        boolean canaryPersisted = postCanary2a != null && nearlyEqual(ByteUtil.f32le(postCanary2a, OFF_LEFT_EQ_250HZ), ByteUtil.f32le(canary2a, OFF_LEFT_EQ_250HZ));
        boolean canaryStayedOriginal = postCanary2a != null && nearlyEqual(ByteUtil.f32le(postCanary2a, OFF_LEFT_EQ_250HZ), ByteUtil.f32le(source2a, OFF_LEFT_EQ_250HZ));
        boolean restoreOriginal = postRestore2a != null && nearlyEqual(ByteUtil.f32le(postRestore2a, OFF_LEFT_EQ_250HZ), ByteUtil.f32le(source2a, OFF_LEFT_EQ_250HZ));
        log(prefix + " interpretation inputs: enableSawControl52=" + enableSummary.saw52()
                + ", canaryAacpSaw52=" + afterCanaryAacp.saw52()
                + ", optional54Saw52=" + (optional54Original != null && optional54Original.saw52())
                + ", canaryPersistedByLeftEq0=" + canaryPersisted
                + ", canaryStayedOriginalByLeftEq0=" + canaryStayedOriginal
                + ", restoreReturnedOriginalByFinalFloat=" + restoreOriginal + ".");
        if (canaryPersisted) {
            log(prefix + " interpretation: strong win. With HEARING_AID ON and the LibrePods byte[2]=0x64 write shape, ATT 0x002A reflected the canary. That means earlier failures were probably due to missing the exact LibrePods HEARING_AID CCCD / semantic-field write sequence.");
        } else if (canaryStayedOriginal && afterCanaryAacp.saw52()) {
            log(prefix + " interpretation: the canary did not show in ATT readback, but AACP emitted 0x0052 after the settings write. This suggests the command was parsed but readback is not the committed store, or there is an async/apply path still missing.");
        } else if (canaryStayedOriginal) {
            log(prefix + " interpretation: even after HEARING_AID ON and byte[2]=0x64, ATT readback stayed original and no clear AACP acceptance appeared. The remaining missing piece is likely a different commit/authorization step or a different settings characteristic.");
        } else if (postCanary2a == null) {
            log(prefix + " interpretation: the post-canary read failed, so the write outcome is unknown. Rerun with stable connection before changing hypotheses.");
        } else {
            log(prefix + " interpretation: mixed result. Inspect byte diffs and first 0x0052/0x0053 packets; the AirPods may normalize the settings payload into a shape other than the exact canary.");
        }
    }

    private PacketRecord chooseCurrentSession53(AacpStreamSummary pre, AacpStreamSummary afterCccd, AacpStreamSummary afterReads) {
        if (afterReads != null && afterReads.first53 != null) return afterReads.first53;
        if (afterCccd != null && afterCccd.first53 != null) return afterCccd.first53;
        if (pre != null && pre.first53 != null) return pre.first53;
        return null;
    }

    private boolean validate53ForOptional54(byte[] payload) {
        if (payload == null) return false;
        int declared = payload.length >= 2 ? ByteUtil.u16le(payload, 0) : -1;
        log("v31 optional 0x0053 validation: len=" + payload.length + ", declared/body-ish=" + declared + ", float summary=" + ByteUtil.floatSummary(payload));
        if (payload.length < 10) return false;
        if (declared != payload.length - 2) return false;
        if (ByteUtil.u8(payload[2]) != 0x02 || ByteUtil.u8(payload[3]) != 0x00 || ByteUtil.u8(payload[4]) != 0x02 || ByteUtil.u8(payload[5]) != 0x02) return false;
        if (((payload.length - 6) % 4) != 0) return false;
        return (payload.length - 6) / 4 == 32;
    }

    private void logKnownHearingControl(String label, AacpStreamSummary... summaries) {
        int[] ids = new int[]{CC_HEARING_AID, CC_HEARING_ASSIST_CONFIG, CC_PPE_TOGGLE_CONFIG, CC_PPE_CAP_LEVEL_CONFIG};
        String[] names = new String[]{"HEARING_AID", "HEARING_ASSIST_CONFIG", "PPE_TOGGLE_CONFIG", "PPE_CAP_LEVEL_CONFIG"};
        for (int i = 0; i < ids.length; i++) {
            byte[] v = lastControlValue(summaries, ids[i]);
            if (v != null) {
                log(label + String.format(Locale.US, ": control %s id=0x%02X value=%s", names[i], ids[i], ByteUtil.hex(v)));
            }
        }
    }

    private int firstDataByteForControl(AacpStreamSummary s1, AacpStreamSummary s2, AacpStreamSummary s3, int id) {
        byte[] v = lastControlValue(new AacpStreamSummary[]{s1, s2, s3}, id);
        return v == null || v.length == 0 ? -1 : ByteUtil.u8(v[0]);
    }

    private byte[] lastControlValue(AacpStreamSummary[] summaries, int id) {
        byte[] result = null;
        if (summaries == null) return null;
        for (AacpStreamSummary s : summaries) {
            if (s == null) continue;
            for (PacketRecord r : s.packets) {
                if (r == null || r.command != 0x0009) continue;
                byte[] p = r.payload();
                if (p.length >= 5 && ByteUtil.u8(p[0]) == id) {
                    result = ByteUtil.copyOfRange(p, 1, p.length);
                }
            }
        }
        return result;
    }

    private float chooseDelta(float old, float step) {
        if (old + step <= 1.0f) return step;
        if (old - step >= 0.0f) return -step;
        return Float.NaN;
    }

    private float finalFloat(byte[] v) {
        if (v == null || v.length < 4) return Float.NaN;
        return ByteUtil.f32le(v, v.length - 4);
    }

    private boolean nearlyEqual(float a, float b) {
        if (!Float.isFinite(a) || !Float.isFinite(b)) return false;
        return Math.abs(a - b) < 0.000001f;
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

    private void logFirstInteresting(String label, AacpStreamSummary summary) {
        if (summary == null) return;
        if (summary.first52 != null) logPacketDetails(label + " first 0x0052", summary.first52.packet);
        if (summary.first53 != null) logPacketDetails(label + " first 0x0053", summary.first53.packet);
        if (summary.first55 != null) logPacketDetails(label + " first 0x0055", summary.first55.packet);
        if (summary.first17 != null) logPacketDetails(label + " first 0x0017", summary.first17.packet);
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
