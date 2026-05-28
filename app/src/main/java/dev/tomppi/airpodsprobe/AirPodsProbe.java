package dev.tomppi.airpodsprobe;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class AirPodsProbe {
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 3500;

    private final ProbeLog log;

    AirPodsProbe(ProbeLog log) {
        this.log = log;
    }

    void probe(BluetoothDevice device, boolean doAacp, boolean doAtt, boolean tryRaw, String rawHex) {
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
