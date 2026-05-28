package dev.tomppi.airpodsprobe;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ProbeLog {
    private final StringBuilder buffer = new StringBuilder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView target;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    ProbeLog(TextView target) {
        this.target = target;
    }

    synchronized void clear() {
        buffer.setLength(0);
        main.post(() -> target.setText(""));
    }

    void line(String msg) {
        String line = fmt.format(new Date()) + "  " + msg + "\n";
        synchronized (this) {
            buffer.append(line);
        }
        main.post(() -> target.append(line));
    }

    synchronized String text() {
        return buffer.toString();
    }
}
