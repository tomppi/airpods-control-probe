package com.airpodsprobe.v26;

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

final class AacpV26Probe {
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

    AacpV26Probe(BluetoothDevice device, LogSink log) {
        this.device = device;
        this.log = log;
    }

    void run() {
        log("=== AirPods AACP v26 probe started ===");
        log("Device: " + safeName(device) + " / " + device.getAddress());
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.");
        log("It does not install or replace the Xposed module.");
        log("--- v26 ATT 0x002A reversible Q8-step canary write test ---");
        log("v25 proved that ATT handle 0x002A accepts a one-ULP canary Write Request, but read-back stayed byte-for-byte original and no direct AACP response appeared.");
        log("v26 therefore tests the quantization hypothesis with a still-small reversible canary: change only the final float32-le value by one Q8-sized step (1/256), read back, then restore the original bytes.");
        log("v26 uses the same winning v20/v22 capture path, reads ATT 0x002A before and after CCCD 0x0022, and requires the value to be stable before writing anything.");
        log("v26 sends no AACP 0x0052, 0x0053, 0x0054, or 0x0055 candidates. The only non-current write is the guarded Q8-step ATT 0x002A canary, followed immediately by an original-value restore.");
        log("The canary guard requires the observed 104-byte 0x002A shape, selector byte0=0x02, length word [2..3]=96, and a finite final float where +/- 1/256 remains inside [0,1].");

        boolean completed = false;
        final int attempts = 2;
        for (int i = 1; i <= attempts; i++) {
            completed = runCaptureAndWriteAttempt(i, attempts);
            if (completed) break;
            if (i < attempts) {
                log("v26: attempt " + i + " did not reach the guarded reversible 0x002A canary path. Sleeping briefly, then retrying the same safe path once.");
                sleep(1300);
            }
        }
        if (!completed) {
            log("v26 final result: no stable guarded ATT 0x002A value was available for a reversible Q8-step canary. No canary write was sent.");
        }
        log("=== Probe finished ===");
    }

    private boolean runCaptureAndWriteAttempt(int attempt, int totalAttempts) {
        BluetoothSocket aacp = null;
        BluetoothSocket att = null;
        AacpStreamSummary preSetter = new AacpStreamSummary();
        AacpStreamSummary afterCccd = new AacpStreamSummary();
        AacpStreamSummary afterPostCccdReads = new AacpStreamSummary();
        AacpStreamSummary postCanary = new AacpStreamSummary();
        AacpStreamSummary postRestore = new AacpStreamSummary();
        byte[] before2a = null;
        byte[] postCccd2a = null;
        byte[] afterCanary2a = null;
        byte[] afterRestore2a = null;

        String prefix = "v26 attempt " + attempt + "/" + totalAttempts;
        try {
            log("--- " + prefix + ": starting current-session capture path ---");
            log(prefix + ": connecting AACP PSM " + AACP_PSM + ".");
            aacp = connectL2cap(device, AACP_PSM, true, prefix + " AACP");
            if (aacp == null) {
                log(prefix + " abort: failed to connect AACP PSM " + AACP_PSM + ".");
                return false;
            }

            preSetter.addAll(runAacpInit(aacp, prefix + " winning-path init"));
            drainAacp(aacp, prefix + " post-init before ATT", 140, 2800, 140, preSetter);

            log(prefix + ": connecting ATT PSM " + ATT_PSM + ".");
            att = connectL2cap(device, ATT_PSM, false, prefix + " ATT");
            if (att == null) {
                log(prefix + " abort: failed to connect ATT PSM " + ATT_PSM + ".");
                return false;
            }

            drainAtt(att, prefix + " ATT stale after open", 24, 1300);
            drainAacp(aacp, prefix + " after ATT open", 140, 4200, 160, preSetter);

            attRead(att, HANDLE_SIBLING_21, prefix + " pre-CCCD sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, prefix + " pre-CCCD sibling 0x0024");
            before2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " pre-CCCD 0x002A");
            if (before2a != null) {
                log(prefix + " pre-CCCD 0x002A value: " + ByteUtil.hex(before2a));
                decode2a(prefix + " pre-CCCD 0x002A decode", before2a);
            }
            drainAacp(aacp, prefix + " after initial ATT reads", 140, 3300, 140, preSetter);

            log(prefix + ": enabling winning trigger: 0x0021 notify CCCD 0x0022 at handle 0x0022 = 01 00.");
            boolean cccdOk = attWriteRequest(att, HANDLE_CCCD_21, ByteUtil.bytes(0x01, 0x00), prefix + " enable winning CCCD 0x0022");
            log(prefix + ": winning CCCD 0x0022 write result: " + cccdOk);
            drainAtt(att, prefix + " ATT after winning CCCD 0x0022", 40, 1900);
            drainAacp(aacp, prefix + " AACP direct drain after winning CCCD 0x0022", 140, 12000, 240, afterCccd);

            log(prefix + ": continuing past the v21 abort point with the v20-style post-CCCD final ATT reads.");
            attRead(att, HANDLE_SIBLING_21, prefix + " post-CCCD sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, prefix + " post-CCCD sibling 0x0024");
            postCccd2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " post-CCCD 0x002A");
            if (postCccd2a != null) {
                log(prefix + " post-CCCD 0x002A value: " + ByteUtil.hex(postCccd2a));
                decode2a(prefix + " post-CCCD 0x002A decode", postCccd2a);
                if (before2a != null) log(prefix + " post-CCCD 0x002A equals pre-CCCD: " + ByteUtil.equalsBytes(before2a, postCccd2a));
            }
            drainAacp(aacp, prefix + " final capture deep drain after post-CCCD reads", 140, 14500, 320, afterPostCccdReads);

            summarizeStream(prefix + " pre-CCCD stream", preSetter);
            summarizeStream(prefix + " direct post-CCCD stream", afterCccd);
            summarizeStream(prefix + " post-CCCD-read deep stream", afterPostCccdReads);

            PacketRecord context53 = null;
            String context53Label = null;
            if (afterPostCccdReads.first53 != null) {
                context53 = afterPostCccdReads.first53;
                context53Label = "post-CCCD final-read/deep-drain 0x0053";
            } else if (afterCccd.first53 != null) {
                context53 = afterCccd.first53;
                context53Label = "direct post-CCCD 0x0053";
            } else if (preSetter.first53 != null) {
                context53 = preSetter.first53;
                context53Label = "early current-session 0x0053";
            }

            if (context53 != null) {
                logPacketDetails(prefix + " context " + context53Label, context53.packet);
            } else {
                log(prefix + " note: no current-session AACP 0x0053 context was captured, but v26 can still test ATT 0x002A only if the ATT value is stable.");
            }
            if (afterCccd.first55 != null) logPacketDetails(prefix + " context direct post-CCCD first 0x0055", afterCccd.first55.packet);

            if (postCccd2a == null) {
                log(prefix + " abort: post-CCCD ATT 0x002A read failed, so there is no current value to write back. No ATT write was sent.");
                return false;
            }
            if (before2a == null) {
                log(prefix + " abort: pre-CCCD ATT 0x002A read failed, so stability cannot be verified. No ATT write was sent.");
                return false;
            }
            if (!ByteUtil.equalsBytes(before2a, postCccd2a)) {
                log(prefix + " abort: ATT 0x002A changed between pre-CCCD and post-CCCD reads. Refusing reversible canary because the value is not stable.");
                log(prefix + " pre-CCCD 0x002A:  " + ByteUtil.hex(before2a));
                log(prefix + " post-CCCD 0x002A: " + ByteUtil.hex(postCccd2a));
                return false;
            }
            if (!validate2aForCanary(postCccd2a)) {
                log(prefix + " abort: current ATT 0x002A value did not pass the conservative reversible-canary guard. No canary write was sent.");
                return false;
            }

            byte[] canary2a = deriveQ8StepCanary2a(postCccd2a);
            if (canary2a == null || ByteUtil.equalsBytes(canary2a, postCccd2a)) {
                log(prefix + " abort: failed to derive a distinct Q8-step canary from the current ATT 0x002A value. No canary write was sent.");
                return false;
            }

            int finalFloatOffset = postCccd2a.length - 4;
            float originalFinal = ByteUtil.f32le(postCccd2a, finalFloatOffset);
            float canaryFinal = ByteUtil.f32le(canary2a, finalFloatOffset);
            log(prefix + " guarded 0x002A canary source is stable across pre/post-CCCD reads.");
            log(prefix + " original 0x002A payload length=" + postCccd2a.length + ", bytes=" + ByteUtil.hex(postCccd2a));
            log(prefix + " Q8-step canary changes only final float32-le at offset " + finalFloatOffset
                    + ": " + originalFinal + " (" + f32Bytes(postCccd2a, finalFloatOffset) + ") -> "
                    + canaryFinal + " (" + f32Bytes(canary2a, finalFloatOffset) + "), delta=" + (canaryFinal - originalFinal));
            log(prefix + " Q8-step canary payload bytes=" + ByteUtil.hex(canary2a));

            boolean canaryWriteAttempted = false;
            boolean canaryWriteOk = false;
            boolean restoreWriteOk = false;
            try {
                log(prefix + ": sending one ATT Write Request to handle 0x002A with the Q8-step canary value.");
                canaryWriteAttempted = true;
                canaryWriteOk = attWriteRequest(att, HANDLE_HEARING_CONFIG, canary2a, prefix + " Q8-step canary write 0x002A");
                log(prefix + ": Q8-step canary ATT 0x002A write result: " + canaryWriteOk);

                drainAtt(att, prefix + " ATT after Q8-step canary 0x002A write", 40, 2200);
                drainAacp(aacp, prefix + " AACP direct drain after Q8-step canary ATT 0x002A write", 140, 10000, 260, postCanary);

                attRead(att, HANDLE_SIBLING_21, prefix + " post-canary sibling 0x0021");
                attRead(att, HANDLE_SIBLING_24, prefix + " post-canary sibling 0x0024");
                afterCanary2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " post-canary 0x002A");
                if (afterCanary2a != null) {
                    log(prefix + " post-canary 0x002A value: " + ByteUtil.hex(afterCanary2a));
                    decode2a(prefix + " post-canary 0x002A decode", afterCanary2a);
                    log(prefix + " post-canary 0x002A equals canary: " + ByteUtil.equalsBytes(canary2a, afterCanary2a));
                    log(prefix + " post-canary 0x002A equals original: " + ByteUtil.equalsBytes(postCccd2a, afterCanary2a));
                }

                drainAacp(aacp, prefix + " final deep drain after post-canary reads", 140, 7500, 260, postCanary);
                summarizeStream(prefix + " direct post-canary-write stream", postCanary);
            } finally {
                if (canaryWriteAttempted) {
                    log(prefix + ": restoring original 0x002A bytes with ATT Write Request because the canary write was attempted, regardless of canary response/read-back result.");
                    try {
                        restoreWriteOk = attWriteRequest(att, HANDLE_HEARING_CONFIG, postCccd2a, prefix + " restore original 0x002A after canary");
                        log(prefix + ": restore-original ATT 0x002A write result: " + restoreWriteOk);
                        drainAtt(att, prefix + " ATT after restore-original 0x002A write", 40, 2200);
                        drainAacp(aacp, prefix + " AACP direct drain after restore-original ATT 0x002A write", 140, 7000, 220, postRestore);
                        attRead(att, HANDLE_SIBLING_21, prefix + " post-restore sibling 0x0021");
                        attRead(att, HANDLE_SIBLING_24, prefix + " post-restore sibling 0x0024");
                        afterRestore2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " post-restore 0x002A");
                        if (afterRestore2a != null) {
                            log(prefix + " post-restore 0x002A value: " + ByteUtil.hex(afterRestore2a));
                            decode2a(prefix + " post-restore 0x002A decode", afterRestore2a);
                            log(prefix + " post-restore 0x002A equals original: " + ByteUtil.equalsBytes(postCccd2a, afterRestore2a));
                            log(prefix + " post-restore 0x002A equals canary: " + ByteUtil.equalsBytes(canary2a, afterRestore2a));
                        }
                        drainAacp(aacp, prefix + " final deep drain after restore reads", 140, 5500, 220, postRestore);
                        summarizeStream(prefix + " post-restore stream", postRestore);
                    } catch (Exception restoreError) {
                        log(prefix + " RESTORE ERROR: attempted to restore original 0x002A but hit "
                                + restoreError.getClass().getSimpleName() + ": " + restoreError.getMessage());
                    }
                } else {
                    log(prefix + ": canary write was never attempted, so no restore write was needed.");
                }
            }

            log(prefix + " result summary:");
            log("  Q8-step canary ATT 0x002A write attempted: " + canaryWriteAttempted);
            log("  Q8-step canary ATT 0x002A write request accepted: " + canaryWriteOk);
            log("  restore-original ATT 0x002A write request accepted: " + restoreWriteOk);
            log("  had current-session 0x0053 context before canary: " + (context53 != null));
            log("  saw direct post-canary AACP 0x0055 response candidate: " + postCanary.saw55());
            log("  saw direct post-canary AACP 0x0053 refresh: " + postCanary.saw53());
            log("  saw direct post-canary AACP 0x0052 status: " + postCanary.saw52());
            log("  saw post-restore AACP 0x0055 response candidate: " + postRestore.saw55());
            if (afterCanary2a != null) {
                log("  post-canary ATT 0x002A equals canary: " + ByteUtil.equalsBytes(canary2a, afterCanary2a));
                log("  post-canary ATT 0x002A equals original: " + ByteUtil.equalsBytes(postCccd2a, afterCanary2a));
            } else {
                log("  post-canary ATT 0x002A comparison unavailable: afterCanary=false");
            }
            if (afterRestore2a != null) {
                log("  final restored ATT 0x002A equals original: " + ByteUtil.equalsBytes(postCccd2a, afterRestore2a));
            } else {
                log("  final restored ATT 0x002A comparison unavailable: afterRestore=false");
            }
            if (postCanary.first55 != null) logPacketDetails(prefix + " first post-canary 0x0055", postCanary.first55.packet);
            if (postCanary.first53 != null) logPacketDetails(prefix + " first post-canary 0x0053", postCanary.first53.packet);
            if (postCanary.first52 != null) logPacketDetails(prefix + " first post-canary 0x0052", postCanary.first52.packet);
            if (postRestore.first55 != null) logPacketDetails(prefix + " first post-restore 0x0055", postRestore.first55.packet);
            if (postRestore.first53 != null) logPacketDetails(prefix + " first post-restore 0x0053", postRestore.first53.packet);
            if (postRestore.first52 != null) logPacketDetails(prefix + " first post-restore 0x0052", postRestore.first52.packet);

            if (canaryWriteOk && afterCanary2a != null && ByteUtil.equalsBytes(canary2a, afterCanary2a)) {
                log(prefix + " interpretation: ATT 0x002A is semantically writable at byte level: the Q8-step canary read back exactly. Restore status should be checked before any larger test.");
            } else if (canaryWriteOk && afterCanary2a != null && ByteUtil.equalsBytes(postCccd2a, afterCanary2a)) {
                log(prefix + " interpretation: ATT accepted the canary write, but 0x002A read-back stayed original. That suggests the app ignored/quantized the Q8-step change or the write path needs a commit/refresh trigger.");
            } else if (canaryWriteOk) {
                log(prefix + " interpretation: ATT accepted the canary write, but read-back did not match either original or canary. Inspect the exact bytes before any next step.");
            } else {
                log(prefix + " interpretation: ATT 0x002A rejected the Q8-step canary write, despite accepting exact-current no-op in v24.");
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

    private boolean validate2aForCanary(byte[] value) {
        if (value == null) return false;
        log("v26 selected ATT 0x002A canary validation: len=" + value.length + ", nonZero=" + ByteUtil.nonZero(value));
        decode2a("v26 selected ATT 0x002A canary source decode", value);
        if (value.length != 104) {
            log("v26 0x002A canary guard: refusing because the observed safe shape is exactly 104 bytes, but current length is " + value.length + ".");
            return false;
        }
        if (ByteUtil.u8(value[0]) != 0x02) {
            log(String.format(Locale.US, "v26 0x002A canary guard: refusing because byte0 is 0x%02X, not the observed stable 0x02 profile/config selector.", ByteUtil.u8(value[0])));
            return false;
        }
        int bodyWord = ByteUtil.u16le(value, 2);
        if (bodyWord != 96) {
            log("v26 0x002A canary guard: refusing because the word at [2..3] is " + bodyWord + ", not the observed stable 96.");
            return false;
        }
        float finalFloat = ByteUtil.f32le(value, value.length - 4);
        if (Float.isNaN(finalFloat) || Float.isInfinite(finalFloat)) {
            log("v26 0x002A canary guard: refusing because the final float is not finite: " + finalFloat);
            return false;
        }
        if (finalFloat < 0.0f || finalFloat > 1.0f) {
            log("v26 0x002A canary guard: refusing because the final float is outside the conservative [0,1] range: " + finalFloat);
            return false;
        }
        return true;
    }

    private byte[] deriveQ8StepCanary2a(byte[] original) {
        if (!validate2aForCanary(original)) return null;
        byte[] out = Arrays.copyOf(original, original.length);
        int off = out.length - 4;
        float current = ByteUtil.f32le(out, off);
        final float step = 1.0f / 256.0f;
        float canary;
        if (current <= 1.0f - step) {
            canary = current + step;
        } else {
            canary = current - step;
        }
        if (Float.isNaN(canary) || Float.isInfinite(canary) || canary < 0.0f || canary > 1.0f || canary == current) {
            log("v26 0x002A canary guard: derived Q8-step canary is not safe/distinct. current=" + current + ", step=" + step + ", canary=" + canary);
            return null;
        }
        writeF32le(out, off, canary);
        return out;
    }

    private void writeF32le(byte[] b, int off, float value) {
        int bits = Float.floatToRawIntBits(value);
        b[off] = (byte) (bits & 0xFF);
        b[off + 1] = (byte) ((bits >> 8) & 0xFF);
        b[off + 2] = (byte) ((bits >> 16) & 0xFF);
        b[off + 3] = (byte) ((bits >> 24) & 0xFF);
    }

    private String f32Bytes(byte[] b, int off) {
        return ByteUtil.hex(ByteUtil.copyOfRange(b, off, off + 4));
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
