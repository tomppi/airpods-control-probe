package com.airpodsprobe.v29matrix;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_BT_CONNECT = 19419;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText macEdit;
    private TextView logView;
    private Button startButton;
    private Button copyButton;
    private final StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        ensureBluetoothPermission();
        prefillAirPodsAddress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("AirPods AACP v29 Matrix Probe");
        title.setTextSize(20);
        title.setGravity(Gravity.START);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("0x0054 validation matrix: captures current 0x0053, then uses delayed 0x0052 as an acceptance oracle for exact original, single-float Q8 mutations, all-32 uniform Q8 mutation, and exact-original restores. No ATT 0x002A writes.");
        desc.setPadding(0, dp(8), 0, dp(8));
        root.addView(desc, new LinearLayout.LayoutParams(-1, -2));

        macEdit = new EditText(this);
        macEdit.setHint("AirPods MAC address, e.g. BC:80:4E:8D:E4:0B");
        macEdit.setSingleLine(true);
        macEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        root.addView(macEdit, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, dp(8));
        startButton = new Button(this);
        startButton.setText("Run v29 matrix");
        startButton.setOnClickListener(v -> startProbe());
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, -2, 1));

        copyButton = new Button(this);
        copyButton.setText("Copy log");
        copyButton.setOnClickListener(v -> copyLog());
        buttons.addView(copyButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setText("Ready.\n");

        ScrollView scroller = new ScrollView(this);
        scroller.addView(logView, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT }, REQ_BT_CONNECT);
        }
    }

    @SuppressLint("MissingPermission")
    private void prefillAirPodsAddress() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return;
            BluetoothDevice fallback = null;
            for (BluetoothDevice d : bonded) {
                if (fallback == null) fallback = d;
                String name = d.getName();
                if (name != null && name.toLowerCase(Locale.US).contains("airpods")) {
                    macEdit.setText(d.getAddress());
                    appendLog("Prefilled bonded AirPods: " + name + " / " + d.getAddress());
                    return;
                }
            }
            if (fallback != null) appendLog("No bonded AirPods name found. Enter the MAC manually. First bonded device is " + safeName(fallback) + " / " + fallback.getAddress());
        } catch (Throwable t) {
            appendLog("Could not prefill bonded device: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void startProbe() {
        ensureBluetoothPermission();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth permission is required", Toast.LENGTH_LONG).show();
            return;
        }
        String mac = macEdit.getText().toString().trim();
        if (mac.isEmpty()) {
            Toast.makeText(this, "Enter the AirPods MAC address", Toast.LENGTH_LONG).show();
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth adapter unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(mac);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Invalid MAC address", Toast.LENGTH_LONG).show();
            return;
        }

        logBuffer.setLength(0);
        logView.setText("");
        startButton.setEnabled(false);
        appendLog("Starting v29 matrix probe for " + mac + "...");
        executor.execute(() -> {
            try {
                new AacpV29MatrixProbe(device, this::appendLog).run();
            } catch (Throwable t) {
                appendLog("Probe crashed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                runOnUiThread(() -> startButton.setEnabled(true));
            }
        });
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("AACP v29 matrix probe log", logBuffer.toString()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void appendLog(String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String full = stamp + "  " + line + "\n";
        synchronized (logBuffer) {
            logBuffer.append(full);
        }
        runOnUiThread(() -> {
            logView.append(full);
            View parent = (View) logView.getParent();
            if (parent instanceof ScrollView) {
                parent.post(() -> ((ScrollView) parent).fullScroll(View.FOCUS_DOWN));
            }
        });
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
}
