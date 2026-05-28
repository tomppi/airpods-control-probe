package dev.tomppi.airpodsprobe;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.os.ParcelUuid;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

final class AirPodsProbe {
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 3500;
    private static final int UUID_CONNECT_TIMEOUT_MS = 3500;
    private static final int TYPE_RFCOMM = 1;
    private static final int TYPE_L2CAP = 3;
    private static final UUID UUID_AIRPODS_471 = UUID.fromString("4715650b-5e9d-4ac2-b898-a4fc0aa5df78");
    private static final UUID UUID_AIRPODS_74EC = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");

    private final ProbeLog log;

    AirPodsProbe(ProbeLog log) {
        this.log = log;
    }

    void probe(BluetoothDevice device, boolean doAacp, boolean doAtt, boolean tryRaw, String rawHex, boolean doStability, boolean doHearingWriteVerify, boolean doHearingMethodExperiment, boolean doHearingMapExperiment) {
        log.line("=== AirPods control probe started ===");
        log.line("Device: " + safeName(device) + " / " + device.getAddress());
        log.line("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.");
        log.line("It does not install or replace the Xposed module.");

        if (doAacp) {
            testPsm(device, 4097, false, false, null);
        }

        if (doAtt) {
            testPsm(device, 31, true, false, null);
            testPsm(device, 31, true, true, null);
            testAttWhileAacpHeldOpen(device);
            testAttAfterAacpInit(device);
            testUuidDiscovery(device);
        }

        if (tryRaw) {
            try {
                byte[] raw = Hex.parse(rawHex);
                if (raw.length == 0) {
                    log.line("Raw payload skipped: empty payload.");
                } else {
                    testPsm(device, 31, false, false, raw);
                }
            } catch (Exception e) {
                log.line("Raw payload parse failed: " + e.getMessage());
            }
        }

        if (doStability) {
            testDisconnectStability(device);
        }

        if (doHearingWriteVerify) {
            testHearingAidNoOpWriteVerifier(device);
        }

        if (doHearingMethodExperiment) {
            testHearingAidWriteMethodExperiment(device);
        }

        if (doHearingMapExperiment) {
            testHearingAidV13MapAndMonitor(device);
        }

        log.line("=== Probe finished ===");
    }

    private void testPsm(BluetoothDevice device, int psm, boolean attProbe, boolean afterAacpWarmup, byte[] rawPayload) {
        log.line("--- Testing PSM/channel " + psm + (attProbe ? " as ATT" : "") + (afterAacpWarmup ? " after AACP warm-up" : "") + " ---");

        if (afterAacpWarmup) {
            log.line("Warm-up: opening AACP PSM 4097 first.");
            SocketAttempt warm = tryAllStrategies(device, 4097);
            if (warm.socket != null) {
                log.line("Warm-up succeeded using " + warm.strategy + "; closing warm-up socket.");
                closeQuietly(warm.socket);
                sleep(400);
            } else {
                log.line("Warm-up failed; continuing to PSM " + psm + " anyway.");
            }
        }

        SocketAttempt attempt = tryAllStrategies(device, psm);
        if (attempt.socket == null) {
            log.line("PSM " + psm + " failed with every strategy.");
            return;
        }

        log.line("PSM " + psm + " connected using " + attempt.strategy + ".");
        try {
            if (rawPayload != null) {
                log.line("Sending raw payload to PSM " + psm + ": " + Hex.bytes(rawPayload, rawPayload.length));
                try {
                    writeRaw(attempt.socket, rawPayload);
                    byte[] response = readWithTimeout(attempt.socket, READ_TIMEOUT_MS);
                    if (response != null) {
                        log.line("Raw response: " + Hex.bytes(response, response.length));
                    } else {
                        log.line("Raw payload: no response within " + READ_TIMEOUT_MS + " ms.");
                    }
                } catch (IOException e) {
                    log.line("Raw payload write failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            } else if (attProbe) {
                attRead(attempt.socket, 0x0018, "TRANSPARENCY_CONFIG");
                attRead(attempt.socket, 0x001B, "LOUD_SOUND_REDUCTION");
                attRead(attempt.socket, 0x002A, "HEARING_AID_CONFIG");
            } else {
                log.line("Connected only. No payload sent on this channel.");
            }
        } finally {
            closeQuietly(attempt.socket);
            log.line("PSM " + psm + " socket closed.");
        }
    }


    private void testDisconnectStability(BluetoothDevice device) {
        log.line("--- Stability/disconnect lab: AACP init + ATT hold + repeated safe reads ---");
        log.line("This test is read-only. It keeps AACP PSM 4097 open, opens ATT PSM 31 using the method that worked on this ROM, then repeatedly reads known handles.");
        log.line("Watch for Android ACL_DISCONNECTED broadcasts in the log while this test is running or during the 15-second post-close watch.");
        BluetoothSocket aacpSocket = null;
        BluetoothSocket attSocket = null;
        try {
            SocketAttempt aacp = tryAllStrategies(device, 4097);
            aacpSocket = aacp.socket;
            if (aacpSocket == null) {
                log.line("Stability test aborted: could not open AACP PSM 4097.");
                return;
            }
            log.line("Stability: AACP PSM 4097 connected using " + aacp.strategy + ". Sending init sequence.");
            runAacpInitSequence(aacpSocket);
            log.line("Stability: AACP init sent. Waiting 1000 ms before ATT open.");
            sleep(1000);

            SocketAttempt att = tryAttPostInitPreferredWithRetries(device, "Hearing verifier");
            attSocket = att.socket;
            if (attSocket == null) {
                log.line("Stability test aborted: ATT PSM 31 did not connect even after AACP init.");
                return;
            }
            log.line("Stability: ATT PSM 31 connected using " + att.strategy + ". Starting repeated safe reads.");

            for (int cycle = 1; cycle <= 20; cycle++) {
                log.line(String.format(Locale.US, "Stability cycle %02d/20: safe read set begin", cycle));
                attRead(attSocket, 0x0018, "TRANSPARENCY_CONFIG");
                sleep(200);
                attRead(attSocket, 0x001B, "LOUD_SOUND_REDUCTION");
                sleep(200);
                attRead(attSocket, 0x002A, "HEARING_AID_CONFIG");
                log.line(String.format(Locale.US, "Stability cycle %02d/20: safe read set end", cycle));
                sleep(1700);
            }

            log.line("Stability reads completed. Closing ATT first, then AACP after 2 seconds.");
            closeQuietly(attSocket);
            attSocket = null;
            log.line("Stability: ATT socket closed first. Waiting 2000 ms before closing AACP.");
            sleep(2000);
            closeQuietly(aacpSocket);
            aacpSocket = null;
            log.line("Stability: AACP socket closed. Watching for ACL/profile disconnect broadcasts for 15 seconds.");
            sleep(15000);
            log.line("Stability/disconnect lab finished.");
        } catch (Throwable t) {
            log.line("Stability test crashed/failed: " + t.getClass().getSimpleName() + ": " + rootMessage(t));
        } finally {
            closeQuietly(attSocket);
            closeQuietly(aacpSocket);
        }
    }



    private SocketAttempt tryAttPostInitPreferredWithRetries(BluetoothDevice device, String label) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            log.line(label + ": ATT open attempt " + attempt + "/3.");
            SocketAttempt att = tryAttPostInitPreferred(device);
            if (att.socket != null) return att;
            sleep(900);
        }
        return new SocketAttempt(null, null);
    }

    private void testHearingAidNoOpWriteVerifier(BluetoothDevice device) {
        log.line("--- Hearing Aid 0x2A no-op write verifier ---");
        log.line("This test is intended to be safe: it reads handle 0x002A, writes the exact same value back, then reads it again.");
        log.line("It does not intentionally change hearing-aid values. It only verifies whether ATT write-to-0x2A itself is accepted and stable.");
        BluetoothSocket aacpSocket = null;
        BluetoothSocket attSocket = null;
        try {
            SocketAttempt aacp = tryAllStrategies(device, 4097);
            aacpSocket = aacp.socket;
            if (aacpSocket == null) {
                log.line("Hearing verifier aborted: could not open AACP PSM 4097.");
                return;
            }
            log.line("Hearing verifier: AACP PSM 4097 connected using " + aacp.strategy + ". Sending init sequence.");
            runAacpInitSequence(aacpSocket);
            log.line("Hearing verifier: AACP init sent. Waiting 1000 ms before ATT open.");
            sleep(1000);

            SocketAttempt att = tryAttPostInitPreferredWithRetries(device, "Hearing verifier");
            attSocket = att.socket;
            if (attSocket == null) {
                log.line("Hearing verifier aborted: ATT PSM 31 did not connect after AACP init.");
                return;
            }
            log.line("Hearing verifier: ATT PSM 31 connected using " + att.strategy + ".");

            byte[] before = attReadValue(attSocket, 0x002A, "HEARING_AID_CONFIG before no-op write");
            if (before == null) {
                log.line("Hearing verifier aborted: could not read 0x002A before write.");
                return;
            }
            log.line("Hearing verifier: 0x002A value length before write = " + before.length + " bytes.");
            log.line("Hearing verifier: writing the exact same 0x002A value back as a no-op.");
            byte[] writeResponse = attWriteRequest(attSocket, 0x002A, before, "HEARING_AID_CONFIG no-op write");
            if (writeResponse == null) {
                log.line("Hearing verifier: no write response for no-op 0x002A write.");
            } else if (writeResponse.length > 0 && (writeResponse[0] & 0xFF) == 0x13) {
                log.line("Hearing verifier: AirPods returned ATT Write Response 0x13 for no-op write.");
            } else {
                log.line("Hearing verifier: unexpected no-op write response: " + Hex.bytes(writeResponse, writeResponse.length));
            }

            sleep(600);
            byte[] after = attReadValue(attSocket, 0x002A, "HEARING_AID_CONFIG after no-op write");
            if (after == null) {
                log.line("Hearing verifier: could not read 0x002A after no-op write.");
                return;
            }
            boolean same = Arrays.equals(before, after);
            log.line("Hearing verifier: readback after no-op write is " + (same ? "IDENTICAL" : "DIFFERENT") + ".");
            if (!same) {
                log.line("Hearing verifier WARNING: no-op write changed the 0x2A value. Before=" + Hex.bytes(before, before.length));
                log.line("Hearing verifier WARNING: after=" + Hex.bytes(after, after.length));
            } else {
                log.line("Hearing verifier result: ATT write mechanism works at protocol level for an unchanged 0x2A blob.");
                log.line("If LibrePods changed hearing-aid values still do not persist, the issue is likely payload format / commit / save semantics, not basic ATT connectivity.");
            }
        } catch (Throwable t) {
            log.line("Hearing verifier failed: " + t.getClass().getSimpleName() + ": " + rootMessage(t));
        } finally {
            closeQuietly(attSocket);
            closeQuietly(aacpSocket);
            log.line("Hearing verifier sockets closed.");
        }
    }


    private void testHearingAidV13MapAndMonitor(BluetoothDevice device) {
        log.line("--- v13 AACP hearing-capability + ATT map/notification monitor ---");
        log.line("This test does not randomize payloads. It maps ATT handles around 0x002A/0x002B, enables CCCD modes on 0x002B, then watches AACP/ATT after a controlled 0x2A write.");
        log.line("Goal: determine whether hearing-aid saving needs a nearby ATT handle, notification/indication, or an AACP commit/status message.");

        ControlSession session = null;
        byte[] baseline = null;
        try {
            session = openControlSession(device, "v13 baseline/map");
            if (session.attSocket == null) {
                log.line("v13 aborted: could not open ATT for baseline/map.");
                return;
            }

            log.line("v13: draining AACP after init to catch capability/status messages.");
            drainAacpResponsesVerbose(session.aacpSocket, 6, 650);

            runAttDiscoveryAroundHearing(session.attSocket);
            readNearbyAttHandles(session.attSocket, 0x0028, 0x002F);

            baseline = attReadValue(session.attSocket, 0x002A, "v13 baseline HEARING_AID_CONFIG");
            if (baseline == null || baseline.length < 12) {
                log.line("v13 aborted: could not read baseline 0x002A or value too short.");
                return;
            }
            log.line("v13 baseline 0x2A value: " + Hex.bytes(baseline, baseline.length));
        } finally {
            closeSession(session);
        }

        byte[] minimalMutation = baseline.clone();
        minimalMutation[4] = (byte) (minimalMutation[4] == 0 ? 0x01 : 0x00);

        int[] cccdModes = new int[] {0x0001, 0x0002, 0x0003};
        String[] cccdLabels = new String[] {"notifications only 01 00", "indications only 02 00", "notifications+indications 03 00"};
        for (int i = 0; i < cccdModes.length; i++) {
            runV13CccdCommitProbe(device, cccdModes[i], cccdLabels[i], baseline, minimalMutation);
        }

        ControlSession finalSession = null;
        try {
            finalSession = openControlSession(device, "v13 final check");
            if (finalSession.attSocket != null) {
                byte[] finalValue = attReadValue(finalSession.attSocket, 0x002A, "v13 final HEARING_AID_CONFIG");
                log.line("v13 final value equals baseline: " + Arrays.equals(baseline, finalValue));
            }
        } finally {
            closeSession(finalSession);
        }

        log.line("v13 AACP/ATT map + monitor test complete.");
    }

    private void runV13CccdCommitProbe(BluetoothDevice device, int cccdMode, String modeLabel, byte[] baseline, byte[] mutated) {
        String label = "v13 CCCD " + modeLabel;
        log.line("--- " + label + " ---");
        ControlSession session = null;
        try {
            session = openControlSession(device, label);
            if (session.attSocket == null) {
                log.line(label + ": skipped because ATT did not connect.");
                return;
            }

            log.line(label + ": enabling 0x002B with mode " + String.format(Locale.US, "%02X %02X", cccdMode & 0xFF, (cccdMode >> 8) & 0xFF));
            byte[] mode = new byte[] {(byte) (cccdMode & 0xFF), (byte) ((cccdMode >> 8) & 0xFF)};
            byte[] cccdResponse = attWriteRequest(session.attSocket, 0x002B, mode, label + " write CCCD/control 0x002B");
            log.line(label + ": 0x002B write response: " + (cccdResponse == null ? "null" : Hex.bytes(cccdResponse, cccdResponse.length)));

            drainAttUnsolicited(session.attSocket, label + " after 0x002B enable", 4, 650);
            drainAacpResponsesVerbose(session.aacpSocket, 4, 650);

            byte[] before = attReadValue(session.attSocket, 0x002A, label + " before 0x2A write");
            log.line(label + ": before-write equals baseline: " + Arrays.equals(baseline, before));

            log.line(label + ": writing controlled 0x2A mutation: " + Hex.bytes(mutated, mutated.length));
            byte[] wr = attWriteRequest(session.attSocket, 0x002A, mutated, label + " controlled 0x2A write");
            log.line(label + ": 0x2A write response: " + (wr == null ? "null" : Hex.bytes(wr, wr.length)));

            log.line(label + ": draining ATT unsolicited packets after 0x2A write.");
            drainAttUnsolicited(session.attSocket, label + " after 0x2A write", 8, 700);
            log.line(label + ": draining AACP packets after 0x2A write.");
            drainAacpResponsesVerbose(session.aacpSocket, 8, 700);

            int[] waits = new int[] {0, 250, 1000, 3000};
            for (int wait : waits) {
                if (wait > 0) sleep(wait);
                byte[] rb = attReadValue(session.attSocket, 0x002A, label + " readback after " + wait + "ms");
                if (rb == null) {
                    log.line(label + ": readback failed after " + wait + "ms; stopping this mode.");
                    break;
                }
                log.line(label + ": readback after " + wait + "ms equals mutated: " + Arrays.equals(mutated, rb) + ", equals baseline: " + Arrays.equals(baseline, rb));
            }

            log.line(label + ": attempting final baseline restore write, only in case mutation persisted.");
            attWriteRequest(session.attSocket, 0x002A, baseline, label + " final restore 0x2A");
            sleep(300);
        } catch (Throwable t) {
            log.line(label + ": failed: " + t.getClass().getSimpleName() + ": " + rootMessage(t));
        } finally {
            closeSession(session);
            sleep(1200);
        }
    }

    private void runAttDiscoveryAroundHearing(BluetoothSocket socket) {
        log.line("--- v13 ATT discovery around 0x0028–0x002F ---");
        attFindInformation(socket, 0x0028, 0x002F);
        attReadByTypeCharacteristicDeclarations(socket, 0x0020, 0x0035);
        attFindInformation(socket, 0x0001, 0x0040);
    }

    private void readNearbyAttHandles(BluetoothSocket socket, int start, int end) {
        log.line(String.format(Locale.US, "--- v13 individual safe reads 0x%04X–0x%04X ---", start, end));
        for (int h = start; h <= end; h++) {
            byte[] value = attReadValue(socket, h, String.format(Locale.US, "nearby handle 0x%04X", h));
            if (value == null) {
                log.line(String.format(Locale.US, "nearby handle 0x%04X: read failed/not readable", h));
            }
            sleep(120);
        }
    }

    private void attFindInformation(BluetoothSocket socket, int start, int end) {
        byte[] request = new byte[] {0x04, (byte)(start & 0xFF), (byte)((start >> 8) & 0xFF), (byte)(end & 0xFF), (byte)((end >> 8) & 0xFF)};
        log.line(String.format(Locale.US, "ATT Find Information 0x%04X–0x%04X request: %s", start, end, Hex.bytes(request, request.length)));
        try {
            writeRaw(socket, request);
            byte[] response = readWithTimeout(socket, READ_TIMEOUT_MS);
            if (response == null) {
                log.line("ATT Find Information: no response.");
                return;
            }
            log.line("ATT Find Information response: " + Hex.bytes(response, response.length));
            parseAttFindInformation(response);
        } catch (Exception e) {
            log.line("ATT Find Information failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void parseAttFindInformation(byte[] response) {
        if (response.length == 0) return;
        int opcode = response[0] & 0xFF;
        if (opcode == 0x01) {
            log.line("ATT Find Information error: " + parseAttError(response));
            return;
        }
        if (opcode != 0x05 || response.length < 2) {
            log.line("ATT Find Information: unexpected opcode 0x" + String.format(Locale.US, "%02X", opcode));
            return;
        }
        int format = response[1] & 0xFF;
        int offset = 2;
        if (format == 0x01) {
            while (offset + 3 < response.length) {
                int handle = le16(response, offset);
                int uuid = le16(response, offset + 2);
                log.line(String.format(Locale.US, "  handle 0x%04X uuid 0x%04X%s", handle, uuid, describeAttUuid16(uuid)));
                offset += 4;
            }
        } else if (format == 0x02) {
            while (offset + 17 < response.length) {
                int handle = le16(response, offset);
                byte[] uuid = Arrays.copyOfRange(response, offset + 2, offset + 18);
                log.line(String.format(Locale.US, "  handle 0x%04X uuid128-le %s", handle, Hex.bytes(uuid, uuid.length)));
                offset += 18;
            }
        } else {
            log.line("ATT Find Information: unknown format " + format);
        }
    }

    private void attReadByTypeCharacteristicDeclarations(BluetoothSocket socket, int start, int end) {
        byte[] request = new byte[] {0x08, (byte)(start & 0xFF), (byte)((start >> 8) & 0xFF), (byte)(end & 0xFF), (byte)((end >> 8) & 0xFF), 0x03, 0x28};
        log.line(String.format(Locale.US, "ATT Read By Type characteristic declarations 0x%04X–0x%04X request: %s", start, end, Hex.bytes(request, request.length)));
        try {
            writeRaw(socket, request);
            byte[] response = readWithTimeout(socket, READ_TIMEOUT_MS);
            if (response == null) {
                log.line("ATT Read By Type: no response.");
                return;
            }
            log.line("ATT Read By Type response: " + Hex.bytes(response, response.length));
            parseAttReadByTypeCharacteristicDeclarations(response);
        } catch (Exception e) {
            log.line("ATT Read By Type failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void parseAttReadByTypeCharacteristicDeclarations(byte[] response) {
        if (response.length == 0) return;
        int opcode = response[0] & 0xFF;
        if (opcode == 0x01) {
            log.line("ATT Read By Type error: " + parseAttError(response));
            return;
        }
        if (opcode != 0x09 || response.length < 2) {
            log.line("ATT Read By Type: unexpected opcode 0x" + String.format(Locale.US, "%02X", opcode));
            return;
        }
        int len = response[1] & 0xFF;
        int offset = 2;
        while (len > 0 && offset + len <= response.length) {
            int declHandle = le16(response, offset);
            int props = response[offset + 2] & 0xFF;
            int valueHandle = le16(response, offset + 3);
            String uuidDesc;
            if (len == 7) {
                int uuid = le16(response, offset + 5);
                uuidDesc = String.format(Locale.US, "0x%04X%s", uuid, describeAttUuid16(uuid));
            } else {
                byte[] uuid = Arrays.copyOfRange(response, offset + 5, offset + len);
                uuidDesc = "128-le " + Hex.bytes(uuid, uuid.length);
            }
            log.line(String.format(Locale.US, "  char decl 0x%04X props 0x%02X valueHandle 0x%04X uuid %s%s", declHandle, props, valueHandle, uuidDesc, describeCharProperties(props)));
            offset += len;
        }
    }

    private void drainAttUnsolicited(BluetoothSocket socket, String label, int maxPackets, int timeoutMs) {
        for (int i = 0; i < maxPackets; i++) {
            byte[] p = readWithTimeout(socket, timeoutMs);
            if (p == null) {
                if (i == 0) log.line(label + ": no unsolicited ATT packets.");
                return;
            }
            int opcode = p.length > 0 ? (p[0] & 0xFF) : -1;
            log.line(label + ": unsolicited ATT packet " + (i + 1) + " opcode 0x" + String.format(Locale.US, "%02X", opcode) + ": " + Hex.bytes(p, p.length));
            if (opcode == 0x1B && p.length >= 3) {
                log.line(String.format(Locale.US, "  ATT notification on handle 0x%04X value %s", le16(p, 1), Hex.bytes(Arrays.copyOfRange(p, 3, p.length), Math.max(0, p.length - 3))));
            } else if (opcode == 0x1D && p.length >= 3) {
                log.line(String.format(Locale.US, "  ATT indication on handle 0x%04X value %s; sending Handle Value Confirmation 0x1E", le16(p, 1), Hex.bytes(Arrays.copyOfRange(p, 3, p.length), Math.max(0, p.length - 3))));
                try { writeRaw(socket, new byte[] {0x1E}); } catch (IOException e) { log.line("  ATT indication confirmation failed: " + e.getMessage()); }
            } else if (opcode == 0x01) {
                log.line("  ATT error: " + parseAttError(p));
            }
        }
    }

    private void drainAacpResponsesVerbose(BluetoothSocket socket, int maxPackets, int timeoutMs) {
        for (int i = 0; i < maxPackets; i++) {
            byte[] response = readWithTimeout(socket, timeoutMs);
            if (response == null) {
                if (i == 0) log.line("AACP drain: no packets.");
                return;
            }
            log.line("AACP drain packet " + (i + 1) + ": " + Hex.bytes(response, response.length));
            logAacpPacketAnalysis("drain packet " + (i + 1), response);
        }
    }

    private void logAacpPacketAnalysis(String label, byte[] packet) {
        if (packet == null || packet.length == 0) return;
        if (packet.length >= 4) {
            int type = le16(packet, 0);
            int service = le16(packet, 2);
            log.line(String.format(Locale.US, "  AACP %s header: type/service? 0x%04X / 0x%04X", label, type, service));
        }
        if (packet.length >= 6) {
            int msg = le16(packet, 4);
            log.line(String.format(Locale.US, "  AACP %s message/command? 0x%04X%s", label, msg, describeAacpMessageOrCapability(msg)));
            if (msg == 0x0002) {
                parseAacpCapabilities(packet, 6, label + " capability-list");
            }
        }
        scanAacpForKnownHearingCapabilities(packet, label);
    }

    private void parseAacpCapabilities(byte[] packet, int offset, String label) {
        log.line("  AACP " + label + ": parsing capability bytes from offset " + offset + ".");
        for (int i = offset; i < packet.length; i++) {
            int cap = packet[i] & 0xFF;
            String desc = describeAacpMessageOrCapability(cap);
            if (!desc.isEmpty()) {
                log.line(String.format(Locale.US, "    capability byte offset %d: 0x%02X%s", i, cap, desc));
            }
        }
    }

    private void scanAacpForKnownHearingCapabilities(byte[] packet, String label) {
        int[] interesting = new int[] {0x31, 0xD0, 0xC0, 0x22, 0xD0, 0x28, 0x26};
        boolean any = false;
        for (int cap : interesting) {
            for (int i = 0; i < packet.length; i++) {
                if ((packet[i] & 0xFF) == cap) {
                    if (!any) {
                        log.line("  AACP " + label + " heuristic capability scan:");
                        any = true;
                    }
                    log.line(String.format(Locale.US, "    found byte 0x%02X%s at offset %d", cap, describeAacpMessageOrCapability(cap), i));
                }
            }
        }
    }

    private String describeAacpMessageOrCapability(int value) {
        switch (value & 0xFFFF) {
            case 0x0002: return " (Capabilities message?)";
            case 0x0004: return " (AACP message/service 4?)";
            case 0x0022: return " (hearingAidCapability?)";
            case 0x0031: return " (hearingAidV2Capability?)";
            case 0x00C0: return " (hearingAidCapability 0xC0?)";
            case 0x00D0: return " (hearingTestCapability?)";
            case 0x0028: return " (hearingProtectionPPECapability?)";
            case 0x0026: return " (heartRateMonitorCapability?)";
            default: return "";
        }
    }

    private String describeAttUuid16(int uuid) {
        switch (uuid & 0xFFFF) {
            case 0x2800: return " (Primary Service)";
            case 0x2801: return " (Secondary Service)";
            case 0x2802: return " (Include)";
            case 0x2803: return " (Characteristic Declaration)";
            case 0x2901: return " (Characteristic User Description)";
            case 0x2902: return " (Client Characteristic Configuration / CCCD)";
            case 0x2903: return " (Server Characteristic Configuration)";
            case 0x2A00: return " (Device Name)";
            case 0x2A01: return " (Appearance)";
            default: return "";
        }
    }

    private String describeCharProperties(int props) {
        List<String> names = new ArrayList<>();
        if ((props & 0x02) != 0) names.add("read");
        if ((props & 0x04) != 0) names.add("write-no-response");
        if ((props & 0x08) != 0) names.add("write");
        if ((props & 0x10) != 0) names.add("notify");
        if ((props & 0x20) != 0) names.add("indicate");
        if (names.isEmpty()) return "";
        return " [" + String.join(",", names) + "]";
    }

    private String parseAttError(byte[] p) {
        if (p == null || p.length < 5 || (p[0] & 0xFF) != 0x01) return "not an ATT error";
        int reqOpcode = p[1] & 0xFF;
        int handle = le16(p, 2);
        int err = p[4] & 0xFF;
        return String.format(Locale.US, "requestOpcode=0x%02X handle=0x%04X error=0x%02X%s", reqOpcode, handle, err, describeAttErrorCode(err));
    }

    private String describeAttErrorCode(int err) {
        switch (err & 0xFF) {
            case 0x01: return " (Invalid Handle)";
            case 0x02: return " (Read Not Permitted)";
            case 0x03: return " (Write Not Permitted)";
            case 0x04: return " (Invalid PDU)";
            case 0x05: return " (Insufficient Authentication)";
            case 0x06: return " (Request Not Supported)";
            case 0x07: return " (Invalid Offset)";
            case 0x08: return " (Insufficient Authorization)";
            case 0x0D: return " (Invalid Attribute Value Length)";
            case 0x0E: return " (Unlikely Error)";
            case 0x0F: return " (Insufficient Encryption)";
            default: return "";
        }
    }

    private int le16(byte[] b, int offset) {
        if (b == null || offset + 1 >= b.length) return -1;
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }


    private void testHearingAidWriteMethodExperiment(BluetoothDevice device) {
        log.line("--- Hearing Aid 0x2A isolated write experiment v12 ---");
        log.line("This version opens a fresh AACP+ATT session for each mutation so one bad 0x2A write cannot poison all later tests.");
        log.line("It focuses on whether header-preserved hearing-aid data mutations can ever persist by readback.");

        ControlSession baselineSession = null;
        byte[] baseline;
        try {
            baselineSession = openControlSession(device, "Baseline");
            if (baselineSession.attSocket == null) {
                log.line("v12 aborted: could not open ATT for baseline read.");
                return;
            }
            baseline = attReadValue(baselineSession.attSocket, 0x002A, "v12 baseline HEARING_AID_CONFIG");
            if (baseline == null || baseline.length < 12) {
                log.line("v12 aborted: baseline 0x2A read failed or was too short.");
                return;
            }
            log.line("v12 baseline 0x2A value: " + Hex.bytes(baseline, baseline.length));
        } finally {
            closeSession(baselineSession);
        }

        byte[] headerChanged = baseline.clone();
        if (headerChanged.length >= 4) headerChanged[2] = (byte) 0x64;

        byte[] headerPreservedFloat4 = baseline.clone();
        headerPreservedFloat4[4] = (byte) 0xCD;
        headerPreservedFloat4[5] = (byte) 0xCC;
        headerPreservedFloat4[6] = 0x4C;
        headerPreservedFloat4[7] = 0x3D; // float 0.05 LE

        byte[] headerPreservedFloat8 = baseline.clone();
        headerPreservedFloat8[8] = (byte) 0xCD;
        headerPreservedFloat8[9] = (byte) 0xCC;
        headerPreservedFloat8[10] = 0x4C;
        headerPreservedFloat8[11] = 0x3D; // float 0.05 LE

        byte[] singleByte4 = baseline.clone();
        singleByte4[4] = 0x01;

        byte[] singleByte8 = baseline.clone();
        singleByte8[8] = 0x01;

        byte[] tailByte = baseline.clone();
        tailByte[tailByte.length - 2] = (byte) (tailByte[tailByte.length - 2] ^ 0x01);

        runIsolatedWriteMutation(device, "A-control header byte[2] 0x60->0x64", baseline, headerChanged, WriteMode.REQUEST);
        runIsolatedWriteMutation(device, "E-fix theory header preserved float at bytes[4..7]", baseline, headerPreservedFloat4, WriteMode.REQUEST);
        runIsolatedWriteMutation(device, "H header preserved float at bytes[8..11]", baseline, headerPreservedFloat8, WriteMode.REQUEST);
        runIsolatedWriteMutation(device, "I header preserved single byte[4]=0x01", baseline, singleByte4, WriteMode.REQUEST);
        runIsolatedWriteMutation(device, "J header preserved single byte[8]=0x01", baseline, singleByte8, WriteMode.REQUEST);
        runIsolatedWriteMutation(device, "K tail byte toggle near end", baseline, tailByte, WriteMode.REQUEST);

        ControlSession finalSession = null;
        try {
            finalSession = openControlSession(device, "Final check");
            if (finalSession.attSocket != null) {
                byte[] finalValue = attReadValue(finalSession.attSocket, 0x002A, "v12 final HEARING_AID_CONFIG");
                log.line("v12 final value equals baseline: " + Arrays.equals(baseline, finalValue));
            }
        } finally {
            closeSession(finalSession);
        }

        log.line("v12 isolated write experiment complete.");
    }

    private void runIsolatedWriteMutation(BluetoothDevice device, String label, byte[] baseline, byte[] mutated, WriteMode mode) {
        log.line("--- v12 isolated test: " + label + " ---");
        log.line("Target mutated blob: " + Hex.bytes(mutated, mutated.length));
        ControlSession session = null;
        try {
            session = openControlSession(device, label);
            if (session.attSocket == null) {
                log.line(label + ": skipped because ATT did not connect.");
                return;
            }

            byte[] before = attReadValue(session.attSocket, 0x002A, label + " before-write read");
            log.line(label + ": before-write equals baseline: " + Arrays.equals(baseline, before));

            if (mode == WriteMode.REQUEST) {
                byte[] wr = attWriteRequest(session.attSocket, 0x002A, mutated, label);
                log.line(label + ": write response is " + (wr == null ? "null" : Hex.bytes(wr, wr.length)));
            } else if (mode == WriteMode.COMMAND) {
                attWriteCommand(session.attSocket, 0x002A, mutated, label);
            } else {
                attPrepareExecuteWrite(session.attSocket, 0x002A, mutated, label);
            }

            int[] waits = new int[] {0, 250, 1000};
            boolean anyMutated = false;
            boolean socketDied = false;
            for (int wait : waits) {
                if (wait > 0) sleep(wait);
                byte[] rb = attReadValue(session.attSocket, 0x002A, label + " readback after " + wait + "ms");
                if (rb == null) {
                    log.line(label + ": readback after " + wait + "ms failed; ATT socket likely closed/rejected this write.");
                    socketDied = true;
                    break;
                }
                boolean equalsMutated = Arrays.equals(mutated, rb);
                boolean equalsBaseline = Arrays.equals(baseline, rb);
                log.line(label + ": readback after " + wait + "ms equals mutated target: " + equalsMutated + ", equals baseline: " + equalsBaseline);
                anyMutated |= equalsMutated;
            }

            if (anyMutated) {
                log.line(label + ": MUTATION PERSISTED; restoring baseline now.");
                attWriteRequest(session.attSocket, 0x002A, baseline, "restore after persisted " + label);
                sleep(500);
                byte[] restored = attReadValue(session.attSocket, 0x002A, label + " restore readback");
                log.line(label + ": restore readback equals baseline: " + Arrays.equals(baseline, restored));
            } else if (socketDied) {
                log.line(label + ": mutation did not verify and the ATT socket died. This suggests AirPods rejected/closed on this payload.");
            } else {
                log.line(label + ": mutation was acknowledged or sent, but readback stayed baseline. This suggests the payload is invalid or missing a commit/auth step.");
            }
        } catch (Throwable t) {
            log.line(label + ": isolated test failed: " + t.getClass().getSimpleName() + ": " + rootMessage(t));
        } finally {
            closeSession(session);
            sleep(1200);
        }
    }

    private ControlSession openControlSession(BluetoothDevice device, String label) {
        BluetoothSocket aacpSocket = null;
        BluetoothSocket attSocket = null;
        try {
            SocketAttempt aacp = tryAllStrategies(device, 4097);
            aacpSocket = aacp.socket;
            if (aacpSocket == null) {
                log.line(label + ": could not open AACP PSM 4097.");
                return new ControlSession(null, null);
            }
            log.line(label + ": AACP PSM 4097 connected using " + aacp.strategy + ". Sending init sequence.");
            runAacpInitSequence(aacpSocket);
            sleep(1000);
            SocketAttempt att = tryAttPostInitPreferredWithRetries(device, label);
            attSocket = att.socket;
            if (attSocket == null) {
                log.line(label + ": could not open ATT PSM 31 after AACP init.");
                closeQuietly(aacpSocket);
                return new ControlSession(null, null);
            }
            log.line(label + ": ATT PSM 31 connected using " + att.strategy + ".");
            return new ControlSession(aacpSocket, attSocket);
        } catch (Throwable t) {
            log.line(label + ": openControlSession failed: " + t.getClass().getSimpleName() + ": " + rootMessage(t));
            closeQuietly(attSocket);
            closeQuietly(aacpSocket);
            return new ControlSession(null, null);
        }
    }

    private void closeSession(ControlSession session) {
        if (session == null) return;
        closeQuietly(session.attSocket);
        closeQuietly(session.aacpSocket);
    }

    private enum WriteMode { REQUEST, COMMAND, PREPARE_EXECUTE }

    private void runWriteMutationTest(BluetoothSocket socket, String label, byte[] original, byte[] mutated, WriteMode mode) {
        log.line("--- " + label + " ---");
        log.line("Mutated target blob: " + Hex.bytes(mutated, mutated.length));
        try {
            if (mode == WriteMode.REQUEST) {
                attWriteRequest(socket, 0x002A, mutated, label);
            } else if (mode == WriteMode.COMMAND) {
                attWriteCommand(socket, 0x002A, mutated, label);
            } else {
                attPrepareExecuteWrite(socket, 0x002A, mutated, label);
            }

            readbackSeries(socket, label, mutated);
        } finally {
            restoreOriginal(socket, original, "restore after " + label);
            sleep(500);
            byte[] restored = attReadValue(socket, 0x002A, "readback after restore for " + label);
            log.line(label + ": restore readback equals original: " + Arrays.equals(original, restored));
        }
    }

    private void readbackSeries(BluetoothSocket socket, String label, byte[] expected) {
        int[] waits = new int[] {0, 1000, 3000};
        for (int wait : waits) {
            if (wait > 0) sleep(wait);
            byte[] rb = attReadValue(socket, 0x002A, label + " readback after " + wait + "ms");
            if (rb == null) {
                log.line(label + ": readback after " + wait + "ms failed.");
            } else {
                log.line(label + ": readback after " + wait + "ms equals mutated target: " + Arrays.equals(expected, rb));
            }
        }
    }

    private void restoreOriginal(BluetoothSocket socket, byte[] original, String label) {
        log.line("Restoring original 0x2A using Write Request 0x12 (" + label + ").");
        attWriteRequest(socket, 0x002A, original, label);
    }

    private void attWriteCommand(BluetoothSocket socket, int handle, byte[] value, String name) {
        byte[] request = new byte[3 + value.length];
        request[0] = 0x52;
        request[1] = (byte) (handle & 0xFF);
        request[2] = (byte) ((handle >> 8) & 0xFF);
        System.arraycopy(value, 0, request, 3, value.length);
        log.line(String.format(Locale.US, "ATT write command %s handle 0x%04X request: %s", name, handle, Hex.bytes(request, request.length)));
        try {
            writeRaw(socket, request);
            byte[] response = readWithTimeout(socket, 600);
            if (response == null) {
                log.line("ATT write command: no response expected/received.");
            } else {
                log.line("ATT write command unexpected response: " + Hex.bytes(response, response.length));
            }
        } catch (Exception e) {
            log.line(String.format(Locale.US, "ATT write command 0x%04X failed: %s: %s", handle, e.getClass().getSimpleName(), e.getMessage()));
        }
    }

    private void attPrepareExecuteWrite(BluetoothSocket socket, int handle, byte[] value, String name) {
        log.line("ATT prepare/execute write " + name + " handle " + String.format(Locale.US, "0x%04X", handle) + ", total value bytes=" + value.length);
        int offset = 0;
        int chunkSize = 18;
        while (offset < value.length) {
            int len = Math.min(chunkSize, value.length - offset);
            byte[] p = new byte[5 + len];
            p[0] = 0x16;
            p[1] = (byte) (handle & 0xFF);
            p[2] = (byte) ((handle >> 8) & 0xFF);
            p[3] = (byte) (offset & 0xFF);
            p[4] = (byte) ((offset >> 8) & 0xFF);
            System.arraycopy(value, offset, p, 5, len);
            log.line("Prepare write offset " + offset + ": " + Hex.bytes(p, p.length));
            try {
                writeRaw(socket, p);
                byte[] r = readWithTimeout(socket, READ_TIMEOUT_MS);
                if (r == null) {
                    log.line("Prepare write offset " + offset + ": no response; aborting prepare/execute test.");
                    return;
                }
                log.line("Prepare write offset " + offset + " response: " + Hex.bytes(r, r.length));
                if (r.length == 0 || (r[0] & 0xFF) != 0x17) {
                    log.line("Prepare write offset " + offset + " did not return 0x17; aborting prepare/execute test.");
                    return;
                }
            } catch (Exception e) {
                log.line("Prepare write offset " + offset + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return;
            }
            offset += len;
            sleep(80);
        }

        byte[] exec = new byte[] {0x18, 0x01};
        log.line("Execute write request: " + Hex.bytes(exec, exec.length));
        try {
            writeRaw(socket, exec);
            byte[] r = readWithTimeout(socket, READ_TIMEOUT_MS);
            if (r == null) {
                log.line("Execute write: no response.");
            } else {
                log.line("Execute write response: " + Hex.bytes(r, r.length));
            }
        } catch (Exception e) {
            log.line("Execute write failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private SocketAttempt tryAttPostInitPreferred(BluetoothDevice device) {
        List<SocketFactory> factories = new ArrayList<>();
        factories.add(new SocketFactory("preferred hidden createInsecureL2capSocket(31) after AACP init", () -> invokeSocket(device, "createInsecureL2capSocket", 31)));
        factories.add(new SocketFactory("fallback hidden createL2capSocket(31) after AACP init", () -> invokeSocket(device, "createL2capSocket", 31)));
        if (Build.VERSION.SDK_INT >= 29) {
            factories.add(new SocketFactory("fallback public createInsecureL2capChannel(31) after AACP init", () -> device.createInsecureL2capChannel(31)));
            factories.add(new SocketFactory("fallback public createL2capChannel(31) after AACP init", () -> device.createL2capChannel(31)));
        }
        for (SocketFactory factory : factories) {
            BluetoothSocket socket = null;
            try {
                log.line("Stability: trying " + factory.name + ".");
                socket = factory.create();
                if (socket == null) {
                    log.line("Stability: " + factory.name + " returned null.");
                    continue;
                }
                boolean ok = connectWithTimeout(socket, CONNECT_TIMEOUT_MS);
                if (ok) return new SocketAttempt(socket, factory.name);
                closeQuietly(socket);
            } catch (Throwable t) {
                log.line("Stability: " + factory.name + " failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                closeQuietly(socket);
            }
        }
        return new SocketAttempt(null, null);
    }

    private void testAttWhileAacpHeldOpen(BluetoothDevice device) {
        log.line("--- Testing PSM/channel 31 as ATT while AACP PSM 4097 remains open ---");
        SocketAttempt aacp = null;
        BluetoothSocket aacpSocket = null;
        try {
            log.line("Opening AACP PSM 4097 and keeping it open during ATT probe.");
            aacp = tryAllStrategies(device, 4097);
            aacpSocket = aacp.socket;
            if (aacpSocket == null) {
                log.line("Could not open AACP PSM 4097; skipping held-open ATT test.");
                return;
            }
            log.line("AACP PSM 4097 held open using " + aacp.strategy + ". Waiting 800 ms before ATT probe.");
            sleep(800);

            SocketAttempt att = tryAllStrategies(device, 31);
            if (att.socket == null) {
                log.line("ATT PSM 31 still failed while AACP was held open.");
                return;
            }
            log.line("ATT PSM 31 connected while AACP was held open using " + att.strategy + ". Running safe reads.");
            try {
                attRead(att.socket, 0x0018, "TRANSPARENCY_CONFIG");
                attRead(att.socket, 0x001B, "LOUD_SOUND_REDUCTION");
                attRead(att.socket, 0x002A, "HEARING_AID_CONFIG");
            } finally {
                closeQuietly(att.socket);
                log.line("ATT PSM 31 socket closed.");
            }
        } finally {
            closeQuietly(aacpSocket);
            log.line("AACP PSM 4097 held-open socket closed.");
        }
    }


    private void testUuidDiscovery(BluetoothDevice device) {
        log.line("--- Testing UUID/SDP-resolved sockets for AirPods custom services ---");
        logBluetoothDeviceSocketMethods();
        try {
            android.os.ParcelUuid[] uuids = device.getUuids();
            if (uuids == null || uuids.length == 0) {
                log.line("device.getUuids() returned no UUIDs. Pair/reconnect or run Bluetooth settings discovery first.");
            } else {
                log.line("BluetoothDevice.getUuids():");
                for (android.os.ParcelUuid u : uuids) {
                    log.line("  " + u.getUuid());
                }
            }
        } catch (Throwable t) {
            log.line("Could not read device UUIDs: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        testUuidSocket(device, UUID_AIRPODS_74EC, "AirPods custom UUID 74ec...", false);
        testUuidSocket(device, UUID_AIRPODS_471, "AirPods custom UUID 471...", true);
    }

    private void testUuidSocket(BluetoothDevice device, UUID uuid, String label, boolean tryAttReads) {
        log.line("--- Testing " + label + " / " + uuid + " ---");
        SocketAttempt attempt = tryUuidStrategies(device, uuid);
        if (attempt.socket == null) {
            log.line(label + " failed with every UUID/SDP strategy.");
            return;
        }
        log.line(label + " connected using " + attempt.strategy + ".");
        try {
            if (tryAttReads) {
                log.line("Trying safe ATT read probes on this UUID-connected socket.");
                attRead(attempt.socket, 0x0018, "TRANSPARENCY_CONFIG");
                attRead(attempt.socket, 0x001B, "LOUD_SOUND_REDUCTION");
                attRead(attempt.socket, 0x002A, "HEARING_AID_CONFIG");
            } else {
                log.line("Connected only. This likely maps to the already-working AACP service; no payload sent.");
            }
        } finally {
            closeQuietly(attempt.socket);
            log.line(label + " socket closed.");
        }
    }


    private void testAttAfterAacpInit(BluetoothDevice device) {
        log.line("--- Testing PSM/channel 31 after LibrePods-style AACP init ---");
        BluetoothSocket aacpSocket = null;
        try {
            log.line("Opening AACP PSM 4097, sending init packets, then probing ATT PSM 31 while AACP stays open.");
            SocketAttempt aacp = tryAllStrategies(device, 4097);
            aacpSocket = aacp.socket;
            if (aacpSocket == null) {
                log.line("Could not open AACP PSM 4097; skipping AACP-init ATT test.");
                return;
            }
            log.line("AACP PSM 4097 connected using " + aacp.strategy + ". Sending init sequence.");
            runAacpInitSequence(aacpSocket);
            log.line("AACP init sequence sent. Waiting 1000 ms before ATT probe.");
            sleep(1000);

            SocketAttempt att = tryAllStrategies(device, 31);
            if (att.socket == null) {
                log.line("ATT PSM 31 still failed after AACP init sequence.");
                return;
            }
            log.line("ATT PSM 31 connected after AACP init using " + att.strategy + ". Running safe reads.");
            try {
                attRead(att.socket, 0x0018, "TRANSPARENCY_CONFIG");
                attRead(att.socket, 0x001B, "LOUD_SOUND_REDUCTION");
                attRead(att.socket, 0x002A, "HEARING_AID_CONFIG");
            } finally {
                closeQuietly(att.socket);
                log.line("ATT PSM 31 socket closed.");
            }
        } finally {
            closeQuietly(aacpSocket);
            log.line("AACP PSM 4097 AACP-init socket closed.");
        }
    }

    private void runAacpInitSequence(BluetoothSocket socket) {
        // These are the same safe startup packet shapes used by LibrePods' AACPManager:
        // handshake, SET_FEATURE_FLAGS, then REQUEST_NOTIFICATIONS.
        // The latter two are wrapped in the AACP data header 04 00 04 00.
        byte[] handshake = new byte[] {
                0x00, 0x00, 0x04, 0x00,
                0x01, 0x00, 0x02, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        byte[] setFeatureFlags = aacpData(new byte[] {
                0x4D, 0x00,
                (byte) 0xD7, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        });
        byte[] requestNotifications = aacpData(new byte[] {
                0x0F, 0x00,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        });

        sendAacpInitPacket(socket, "handshake", handshake, true);
        sleep(250);
        sendAacpInitPacket(socket, "set feature flags", setFeatureFlags, true);
        sleep(250);
        sendAacpInitPacket(socket, "request notifications", requestNotifications, true);
        sleep(500);
        drainAacpResponses(socket, 2);
    }

    private byte[] aacpData(byte[] payload) {
        byte[] out = new byte[payload.length + 4];
        out[0] = 0x04;
        out[1] = 0x00;
        out[2] = 0x04;
        out[3] = 0x00;
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    private void sendAacpInitPacket(BluetoothSocket socket, String label, byte[] packet, boolean tryReadResponse) {
        log.line("AACP init send " + label + ": " + Hex.bytes(packet, packet.length));
        try {
            writeRaw(socket, packet);
            if (tryReadResponse) {
                byte[] response = readWithTimeout(socket, 900);
                if (response == null) {
                    log.line("AACP init " + label + ": no immediate response.");
                } else {
                    log.line("AACP init " + label + " response: " + Hex.bytes(response, response.length));
                    logAacpPacketAnalysis("init " + label, response);
                }
            }
        } catch (IOException e) {
            log.line("AACP init " + label + " write failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void drainAacpResponses(BluetoothSocket socket, int maxPackets) {
        for (int i = 0; i < maxPackets; i++) {
            byte[] response = readWithTimeout(socket, 450);
            if (response == null) return;
            log.line("AACP extra response " + (i + 1) + ": " + Hex.bytes(response, response.length));
            logAacpPacketAnalysis("extra response " + (i + 1), response);
        }
    }

    private void attRead(BluetoothSocket socket, int handle, String name) {
        byte[] request = new byte[] {0x0A, (byte) (handle & 0xFF), (byte) ((handle >> 8) & 0xFF)};
        log.line(String.format(Locale.US, "ATT read %s handle 0x%04X request: %s", name, handle, Hex.bytes(request, request.length)));
        try {
            writeRaw(socket, request);
            byte[] response = readWithTimeout(socket, READ_TIMEOUT_MS);
            if (response == null) {
                log.line(String.format(Locale.US, "ATT read 0x%04X: no response within %d ms", handle, READ_TIMEOUT_MS));
                return;
            }
            int opcode = response.length > 0 ? (response[0] & 0xFF) : -1;
            log.line(String.format(Locale.US, "ATT read 0x%04X response opcode 0x%02X: %s", handle, opcode, Hex.bytes(response, response.length)));
            if (opcode == 0x0B) {
                log.line("ATT read success for " + name + ". This handle is reachable.");
            } else if (opcode == 0x01) {
                log.line("ATT error response for " + name + ". Handle reachable, but AirPods rejected/read not permitted.");
            }
        } catch (Exception e) {
            log.line(String.format(Locale.US, "ATT read 0x%04X failed: %s: %s", handle, e.getClass().getSimpleName(), e.getMessage()));
        }
    }


    private byte[] attReadValue(BluetoothSocket socket, int handle, String name) {
        byte[] request = new byte[] {0x0A, (byte) (handle & 0xFF), (byte) ((handle >> 8) & 0xFF)};
        log.line(String.format(Locale.US, "ATT read %s handle 0x%04X request: %s", name, handle, Hex.bytes(request, request.length)));
        try {
            writeRaw(socket, request);
            byte[] response = readWithTimeout(socket, READ_TIMEOUT_MS);
            if (response == null) {
                log.line(String.format(Locale.US, "ATT read value 0x%04X: no response within %d ms", handle, READ_TIMEOUT_MS));
                return null;
            }
            int opcode = response.length > 0 ? (response[0] & 0xFF) : -1;
            log.line(String.format(Locale.US, "ATT read value 0x%04X response opcode 0x%02X: %s", handle, opcode, Hex.bytes(response, response.length)));
            if (opcode != 0x0B) return null;
            byte[] value = new byte[Math.max(0, response.length - 1)];
            if (value.length > 0) System.arraycopy(response, 1, value, 0, value.length);
            log.line(String.format(Locale.US, "ATT read value 0x%04X parsed value: %s", handle, Hex.bytes(value, value.length)));
            return value;
        } catch (Exception e) {
            log.line(String.format(Locale.US, "ATT read value 0x%04X failed: %s: %s", handle, e.getClass().getSimpleName(), e.getMessage()));
            return null;
        }
    }

    private byte[] attWriteRequest(BluetoothSocket socket, int handle, byte[] value, String name) {
        byte[] request = new byte[3 + value.length];
        request[0] = 0x12;
        request[1] = (byte) (handle & 0xFF);
        request[2] = (byte) ((handle >> 8) & 0xFF);
        System.arraycopy(value, 0, request, 3, value.length);
        log.line(String.format(Locale.US, "ATT write %s handle 0x%04X request: %s", name, handle, Hex.bytes(request, request.length)));
        try {
            writeRaw(socket, request);
            byte[] response = readWithTimeout(socket, READ_TIMEOUT_MS);
            if (response == null) {
                log.line(String.format(Locale.US, "ATT write 0x%04X: no response within %d ms", handle, READ_TIMEOUT_MS));
                return null;
            }
            int opcode = response.length > 0 ? (response[0] & 0xFF) : -1;
            log.line(String.format(Locale.US, "ATT write 0x%04X response opcode 0x%02X: %s", handle, opcode, Hex.bytes(response, response.length)));
            return response;
        } catch (Exception e) {
            log.line(String.format(Locale.US, "ATT write 0x%04X failed: %s: %s", handle, e.getClass().getSimpleName(), e.getMessage()));
            return null;
        }
    }

    private void writeRaw(BluetoothSocket socket, byte[] payload) throws IOException {
        OutputStream os = socket.getOutputStream();
        os.write(payload);
        os.flush();
    }

    private SocketAttempt tryAllStrategies(BluetoothDevice device, int psm) {
        List<SocketFactory> factories = new ArrayList<>();
        factories.add(new SocketFactory("hidden createL2capSocket(psm)", () -> invokeSocket(device, "createL2capSocket", psm)));
        factories.add(new SocketFactory("hidden createInsecureL2capSocket(psm)", () -> invokeSocket(device, "createInsecureL2capSocket", psm)));
        if (Build.VERSION.SDK_INT >= 29) {
            factories.add(new SocketFactory("public createL2capChannel(psm)", () -> device.createL2capChannel(psm)));
            factories.add(new SocketFactory("public createInsecureL2capChannel(psm)", () -> device.createInsecureL2capChannel(psm)));
        }

        for (SocketFactory factory : factories) {
            BluetoothSocket socket = null;
            try {
                log.line("Trying " + factory.name + " for PSM " + psm + ".");
                socket = factory.create();
                if (socket == null) {
                    log.line(factory.name + " returned null.");
                    continue;
                }
                boolean ok = connectWithTimeout(socket, CONNECT_TIMEOUT_MS);
                if (ok) {
                    return new SocketAttempt(socket, factory.name);
                }
                closeQuietly(socket);
            } catch (Throwable t) {
                log.line(factory.name + " failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                closeQuietly(socket);
            }
        }
        return new SocketAttempt(null, null);
    }

    private SocketAttempt tryUuidStrategies(BluetoothDevice device, UUID uuid) {
        List<SocketFactory> factories = new ArrayList<>();
        factories.add(new SocketFactory("public createRfcommSocketToServiceRecord(uuid)", () -> device.createRfcommSocketToServiceRecord(uuid)));
        factories.add(new SocketFactory("public createInsecureRfcommSocketToServiceRecord(uuid)", () -> device.createInsecureRfcommSocketToServiceRecord(uuid)));

        for (SocketFactory factory : factories) {
            BluetoothSocket socket = null;
            try {
                log.line("Trying " + factory.name + " for UUID " + uuid + ".");
                socket = factory.create();
                if (socket == null) {
                    log.line(factory.name + " returned null.");
                    continue;
                }
                boolean ok = connectWithTimeout(socket, UUID_CONNECT_TIMEOUT_MS);
                if (ok) {
                    return new SocketAttempt(socket, factory.name);
                }
                closeQuietly(socket);
            } catch (Throwable t) {
                log.line(factory.name + " failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                closeQuietly(socket);
            }
        }

        SocketAttempt dynamic = tryDeclaredCreateSocketMethods(device, uuid, TYPE_L2CAP);
        if (dynamic.socket != null) return dynamic;
        return new SocketAttempt(null, null);
    }

    private void logBluetoothDeviceSocketMethods() {
        log.line("BluetoothDevice socket-related declared methods on this ROM:");
        int count = 0;
        for (Method m : BluetoothDevice.class.getDeclaredMethods()) {
            String n = m.getName();
            String lower = n.toLowerCase(Locale.US);
            if (lower.contains("socket") || lower.contains("l2cap") || lower.contains("rfcomm")) {
                log.line("  " + methodSignature(m));
                count++;
            }
        }
        if (count == 0) {
            log.line("  <none found>");
        }
    }

    private SocketAttempt tryDeclaredCreateSocketMethods(BluetoothDevice device, UUID uuid, int socketType) {
        log.line("Trying dynamic declared createSocket methods for UUID " + uuid + " with socketType=" + socketType + ".");
        int[] portChoices = new int[] {-1, 0, 31, 4097};
        boolean[] secureChoices = new boolean[] {true, false};

        for (Method m : BluetoothDevice.class.getDeclaredMethods()) {
            if (!"createSocket".equals(m.getName())) continue;
            if (!BluetoothSocket.class.isAssignableFrom(m.getReturnType())) continue;
            if (!methodHasUuidLikeParam(m)) {
                log.line("Skipping createSocket without UUID/ParcelUuid parameter: " + methodSignature(m));
                continue;
            }

            for (int port : portChoices) {
                for (boolean secure : secureChoices) {
                    BluetoothSocket socket = null;
                    String strategy = "dynamic " + methodSignature(m) + " type=" + socketType + " port=" + port + " secure=" + secure;
                    try {
                        log.line("Trying " + strategy + ".");
                        socket = invokeDynamicCreateSocket(device, m, socketType, port, secure, uuid);
                        if (socket == null) {
                            log.line("  returned null.");
                            continue;
                        }
                        boolean ok = connectWithTimeout(socket, UUID_CONNECT_TIMEOUT_MS);
                        if (ok) {
                            return new SocketAttempt(socket, strategy);
                        }
                        closeQuietly(socket);
                    } catch (Throwable t) {
                        log.line("  failed: " + t.getClass().getSimpleName() + ": " + rootMessage(t));
                        closeQuietly(socket);
                    }
                }
            }
        }
        log.line("No dynamic declared createSocket method connected for UUID " + uuid + ".");
        return new SocketAttempt(null, null);
    }

    private BluetoothSocket invokeDynamicCreateSocket(BluetoothDevice device, Method m, int socketType, int port, boolean secure, UUID uuid) throws Exception {
        m.setAccessible(true);
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];

        int intCount = 0;
        for (Class<?> t : types) if (t == int.class || t == Integer.TYPE) intCount++;

        int intIndex = 0;
        int boolIndex = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == int.class || t == Integer.TYPE) {
                if (intCount >= 3) {
                    if (intIndex == 0) args[i] = socketType;      // type
                    else if (intIndex == 1) args[i] = -1;         // fd
                    else args[i] = port;                          // port/psm
                } else if (intCount == 2) {
                    if (intIndex == 0) args[i] = socketType;
                    else args[i] = port;
                } else {
                    args[i] = port;
                }
                intIndex++;
            } else if (t == boolean.class || t == Boolean.TYPE) {
                args[i] = secure;
                boolIndex++;
            } else if (t.getName().equals("android.os.ParcelUuid")) {
                args[i] = new ParcelUuid(uuid);
            } else if (t.getName().equals("java.util.UUID")) {
                args[i] = uuid;
            } else {
                throw new NoSuchMethodException("Unsupported parameter type in " + methodSignature(m) + ": " + t.getName());
            }
        }
        Object result = m.invoke(device, args);
        return (BluetoothSocket) result;
    }

    private boolean methodHasUuidLikeParam(Method m) {
        for (Class<?> t : m.getParameterTypes()) {
            String name = t.getName();
            if (name.equals("java.util.UUID") || name.equals("android.os.ParcelUuid")) return true;
        }
        return false;
    }

    private String methodSignature(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].getSimpleName());
        }
        sb.append(')');
        return sb.toString();
    }

    private String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private BluetoothSocket invokeCreateSocket(BluetoothDevice device, int type, int fd, boolean auth, boolean encrypt, int port, UUID uuid) throws Exception {
        Method m = BluetoothDevice.class.getDeclaredMethod("createSocket", int.class, int.class, boolean.class, boolean.class, int.class, ParcelUuid.class);
        m.setAccessible(true);
        Object result = m.invoke(device, type, fd, auth, encrypt, port, new ParcelUuid(uuid));
        return (BluetoothSocket) result;
    }


    private BluetoothSocket invokeSocket(BluetoothDevice device, String methodName, int psm) throws Exception {
        Method m = BluetoothDevice.class.getMethod(methodName, int.class);
        Object result = m.invoke(device, psm);
        return (BluetoothSocket) result;
    }

    private boolean connectWithTimeout(BluetoothSocket socket, int timeoutMs) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<?> future = ex.submit(() -> {
            try {
                socket.connect();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            log.line("connect() timed out after " + timeoutMs + " ms; closing socket.");
            future.cancel(true);
            closeQuietly(socket);
            return false;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.line("connect() failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return false;
        } finally {
            ex.shutdownNow();
        }
    }

    private byte[] readWithTimeout(BluetoothSocket socket, int timeoutMs) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<byte[]> future = ex.submit(() -> {
            InputStream is = socket.getInputStream();
            byte[] buf = new byte[512];
            int read = is.read(buf);
            if (read <= 0) return new byte[0];
            byte[] out = new byte[read];
            System.arraycopy(buf, 0, out, 0, read);
            return out;
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (Exception e) {
            log.line("read() failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            ex.shutdownNow();
        }
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (Exception ignored) {}
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String n = device.getName();
            return n == null ? "<unnamed>" : n;
        } catch (SecurityException e) {
            return "<permission denied>";
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private interface SocketCreator { BluetoothSocket create() throws Exception; }

    private static final class SocketFactory {
        final String name;
        final SocketCreator creator;
        SocketFactory(String name, SocketCreator creator) {
            this.name = name;
            this.creator = creator;
        }
        BluetoothSocket create() throws Exception { return creator.create(); }
    }


    private static final class ControlSession {
        final BluetoothSocket aacpSocket;
        final BluetoothSocket attSocket;
        ControlSession(BluetoothSocket aacpSocket, BluetoothSocket attSocket) {
            this.aacpSocket = aacpSocket;
            this.attSocket = attSocket;
        }
    }

    private static final class SocketAttempt {
        final BluetoothSocket socket;
        final String strategy;
        SocketAttempt(BluetoothSocket socket, String strategy) {
            this.socket = socket;
            this.strategy = strategy;
        }
    }
}
