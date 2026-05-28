package dev.tomppi.airpodsprobe;

import java.util.Locale;

final class Hex {
    private Hex() {}

    static String bytes(byte[] data, int len) {
        if (data == null) return "<null>";
        int n = Math.max(0, Math.min(data.length, len));
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    static byte[] parse(String input) {
        if (input == null) return new byte[0];
        String cleaned = input.replace("0x", "")
                .replace("0X", "")
                .replace(",", " ")
                .replace(";", " ")
                .trim();
        if (cleaned.isEmpty()) return new byte[0];
        String[] parts = cleaned.split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 2) {
                throw new IllegalArgumentException("Bad byte: " + parts[i]);
            }
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }
}
