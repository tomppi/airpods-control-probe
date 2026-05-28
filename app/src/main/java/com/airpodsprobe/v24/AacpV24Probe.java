package com.airpodsprobe.v24;

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

final class AacpV24Probe {
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

    AacpV24Probe(BluetoothDevice device, LogSink log) {
        this.device = device;
        this.log = log;
    }

    void run() {
        log("=== AirPods AACP v24 probe started ===");
        log("Device: " + safeName(device) + " / " + device.getAddress());
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.");
        log("It does not install or replace the Xposed module.");
        log("--- v24 ATT 0x002A exact-current no-op write-back test ---");
        log("v22 and v23 proved that the clean current-session 0x0053 capture is reliable, but both AACP 0x0054 full-payload and body-only no-op echoes were silent and did not change ATT 0x002A.");
        log("v24 therefore tests the next safest setter-path hypothesis: the writable path may be ATT handle 0x002A itself, not AACP 0x0054.");
        log("v24 uses the same winning v20/v22 capture path, reads ATT 0x002A before and after CCCD 0x0022, and requires the value to be stable before writing anything.");
        log("v24 sends exactly one setter-like operation: an ATT Write Request to handle 0x002A containing the exact current 0x002A bytes just read back.");
        log("v24 does not send AACP 0x0052, 0x0053, 0x0054, or 0x0055 candidates.");

        boolean completed = false;
        final int attempts = 2;
        for (int i = 1; i <= attempts; i++) {
            completed = runCaptureAndWriteAttempt(i, attempts);
            if (completed) break;
            if (i < attempts) {
                log("v24: attempt " + i + " did not reach the guarded exact-current 0x002A write-back. Sleeping briefly, then retrying the same safe path once.");
                sleep(1300);
            }
        }
        if (!completed) {
            log("v24 final result: no stable current-session ATT 0x002A value was available for an exact no-op write-back. No ATT write was sent.");
        }
        log("=== Probe finished ===");
    }

    private boolean runCaptureAndWriteAttempt(int attempt, int totalAttempts) {
        BluetoothSocket aacp = null;
        BluetoothSocket att = null;
        AacpStreamSummary preSetter = new AacpStreamSummary();
        AacpStreamSummary afterCccd = new AacpStreamSummary();
        AacpStreamSummary afterPostCccdReads = new AacpStreamSummary();
        AacpStreamSummary postSetter = new AacpStreamSummary();
        byte[] before2a = null;
        byte[] postCccd2a = null;
        byte[] after2a = null;

        String prefix = "v24 attempt " + attempt + "/" + totalAttempts;
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
                log(prefix + " note: no current-session AACP 0x0053 context was captured, but v24 can still test ATT 0x002A only if the ATT value is stable.");
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
                log(prefix + " abort: ATT 0x002A changed between pre-CCCD and post-CCCD reads. Refusing exact write-back because the value is not stable.");
                log(prefix + " pre-CCCD 0x002A:  " + ByteUtil.hex(before2a));
                log(prefix + " post-CCCD 0x002A: " + ByteUtil.hex(postCccd2a));
                return false;
            }
            if (!validate2aForNoop(postCccd2a)) {
                log(prefix + " abort: current ATT 0x002A value did not pass the conservative no-op write-back guard. No ATT write was sent.");
                return false;
            }

            log(prefix + " exact-current 0x002A no-op write-back source is stable across pre/post-CCCD reads.");
            log(prefix + " exact-current 0x002A write-back payload length=" + postCccd2a.length + ", bytes=" + ByteUtil.hex(postCccd2a));
            log(prefix + ": sending exactly one ATT Write Request to handle 0x002A with the exact current 0x002A value.");
            boolean writeOk = attWriteRequest(att, HANDLE_HEARING_CONFIG, postCccd2a, prefix + " exact-current no-op write-back 0x002A");
            log(prefix + ": exact-current ATT 0x002A write-back result: " + writeOk);

            drainAtt(att, prefix + " ATT after exact-current 0x002A write-back", 40, 2200);
            drainAacp(aacp, prefix + " AACP direct drain after exact-current ATT 0x002A write-back", 140, 10000, 260, postSetter);

            attRead(att, HANDLE_SIBLING_21, prefix + " post-write sibling 0x0021");
            attRead(att, HANDLE_SIBLING_24, prefix + " post-write sibling 0x0024");
            after2a = attRead(att, HANDLE_HEARING_CONFIG, prefix + " post-write 0x002A");
            if (after2a != null) {
                log(prefix + " post-write 0x002A value: " + ByteUtil.hex(after2a));
                decode2a(prefix + " post-write 0x002A decode", after2a);
                log(prefix + " post-write 0x002A equals write-back source: " + ByteUtil.equalsBytes(postCccd2a, after2a));
                log(prefix + " post-write 0x002A equals pre-CCCD value: " + ByteUtil.equalsBytes(before2a, after2a));
            }

            drainAacp(aacp, prefix + " final deep drain after post-write reads", 140, 7500, 260, postSetter);
            summarizeStream(prefix + " direct post-ATT-write stream", postSetter);

            log(prefix + " result summary:");
            log("  ATT 0x002A exact-current write request accepted: " + writeOk);
            log("  had current-session 0x0053 context before write: " + (context53 != null));
            log("  saw direct post-ATT-write AACP 0x0055 response candidate: " + postSetter.saw55());
            log("  saw direct post-ATT-write AACP 0x0053 refresh: " + postSetter.saw53());
            log("  saw direct post-ATT-write AACP 0x0052 status: " + postSetter.saw52());
            if (after2a != null) {
                log("  ATT 0x002A changed after exact-current write-back: " + (!ByteUtil.equalsBytes(postCccd2a, after2a)));
            } else {
                log("  ATT 0x002A post-write comparison unavailable: after=false");
            }
            if (postSetter.first55 != null) logPacketDetails(prefix + " first post-ATT-write 0x0055", postSetter.first55.packet);
            if (postSetter.first53 != null) logPacketDetails(prefix + " first post-ATT-write 0x0053", postSetter.first53.packet);
            if (postSetter.first52 != null) logPacketDetails(prefix + " first post-ATT-write 0x0052", postSetter.first52.packet);

            if (writeOk && postSetter.saw55()) {
                log(prefix + " interpretation: ATT 0x002A is a strong setter-path candidate because the exact-current write was accepted and produced a direct AACP 0x0055 response.");
            } else if (writeOk) {
                log(prefix + " interpretation: ATT 0x002A accepted an exact-current write-back, but no direct AACP 0x0055 response was observed. Compare the post-write read and any AACP refreshes.");
            } else {
                log(prefix + " interpretation: ATT 0x002A did not accept the exact-current write request, which argues against plain ATT Write Request as the setter path.");
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

    private boolean validate2aForNoop(byte[] value) {
        if (value == null) return false;
        log("v24 selected ATT 0x002A write-back validation: len=" + value.length + ", nonZero=" + ByteUtil.nonZero(value));
        decode2a("v24 selected ATT 0x002A write-back decode", value);
        if (value.length < 4) return false;
        if (value.length > 512) {
            log("v24 0x002A guard: refusing to write back an unexpectedly large value: " + value.length + " bytes.");
            return false;
        }
        if (ByteUtil.u8(value[0]) != 0x02) {
            log(String.format(Locale.US, "v24 0x002A guard: refusing because byte0 is 0x%02X, not the observed stable 0x02 profile/config selector.", ByteUtil.u8(value[0])));
            return false;
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
