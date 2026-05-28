package com.airpodsprobe.v26;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ByteUtil {
    private ByteUtil() {}

    static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) (values[i] & 0xFF);
        return out;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] copyOfRange(byte[] src, int from, int to) {
        if (from < 0) from = 0;
        if (to > src.length) to = src.length;
        if (to < from) to = from;
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    static int u8(byte b) {
        return b & 0xFF;
    }

    static int u16le(byte[] b, int off) {
        if (b == null || off < 0 || off + 1 >= b.length) return -1;
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static boolean equalsBytes(byte[] a, byte[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    static String hex(byte[] b) {
        if (b == null) return "<null>";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    static String hexShort(byte[] b, int maxBytes) {
        if (b == null) return "<null>";
        if (b.length <= maxBytes) return hex(b);
        byte[] prefix = copyOfRange(b, 0, maxBytes);
        return hex(prefix) + " ... (" + b.length + " bytes)";
    }

    static String nonZero(byte[] b) {
        if (b == null) return "<null>";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < b.length; i++) {
            if ((b[i] & 0xFF) != 0) {
                parts.add("[" + i + "]=0x" + String.format(Locale.US, "%02X", b[i] & 0xFF));
            }
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    static float f32le(byte[] b, int off) {
        if (b == null || off < 0 || off + 3 >= b.length) return Float.NaN;
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }


    static String floatSummaryNoLen(byte[] body) {
        if (body == null || body.length < 8) return "not enough bytes for body-without-length float vector";
        int start = 4; // observed 0x0053 body prefix: 02 00 02 02, followed by float32-le values.
        int count = (body.length - start) / 4;
        if (count <= 0) return "prefix=" + hex(copyOfRange(body, 0, Math.min(4, body.length))) + ", no complete float32 values";
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        StringBuilder first = new StringBuilder();
        int firstCount = Math.min(count, 12);
        for (int i = 0; i < count; i++) {
            float v = f32le(body, start + i * 4);
            if (v < min) min = v;
            if (v > max) max = v;
            if (i < firstCount) {
                if (i > 0) first.append(", ");
                first.append(String.format(Locale.US, "%.6f", v));
            }
        }
        return "prefix=" + hex(copyOfRange(body, 0, Math.min(4, body.length)))
                + ", float32-le count=" + count
                + ", first=" + first
                + ", min=" + min
                + ", max=" + max;
    }

    static String floatSummary(byte[] payloadWithLen) {
        if (payloadWithLen == null || payloadWithLen.length < 10) return "not enough bytes for float vector";
        int declared = u16le(payloadWithLen, 0);
        int start = 6; // len word + 4-byte prefix, as observed in v17/v18.
        int count = (payloadWithLen.length - start) / 4;
        if (count <= 0) return "declared=" + declared + ", no complete float32 values";
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        StringBuilder first = new StringBuilder();
        int firstCount = Math.min(count, 12);
        for (int i = 0; i < count; i++) {
            float v = f32le(payloadWithLen, start + i * 4);
            if (v < min) min = v;
            if (v > max) max = v;
            if (i < firstCount) {
                if (i > 0) first.append(", ");
                first.append(String.format(Locale.US, "%.6f", v));
            }
        }
        return "declared/body-ish length=" + declared
                + ", prefix=" + hex(copyOfRange(payloadWithLen, 2, Math.min(6, payloadWithLen.length)))
                + ", float32-le count=" + count
                + ", first=" + first
                + ", min=" + min
                + ", max=" + max;
    }
}
