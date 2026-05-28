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

    void probe(BluetoothDevice device, boolean doAacp, boolean doAtt, boolean tryRaw, String rawHex, boolean doStability, boolean doHearingWriteVerify) {
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

            SocketAttempt att = tryAttPostInitPreferred(device);
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

            SocketAttempt att = tryAttPostInitPreferred(device);
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

    private static final class SocketAttempt {
        final BluetoothSocket socket;
        final String strategy;
        SocketAttempt(BluetoothSocket socket, String strategy) {
            this.socket = socket;
            this.strategy = strategy;
        }
    }
}
