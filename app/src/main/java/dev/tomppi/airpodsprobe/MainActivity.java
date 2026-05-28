package dev.tomppi.airpodsprobe;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private Spinner deviceSpinner;
    private TextView logView;
    private CheckBox probeAacp;
    private CheckBox probeAtt;
    private CheckBox sendRaw;
    private CheckBox stabilityTest;
    private CheckBox hearingWriteVerifier;
    private CheckBox hearingMethodExperiment;
    private CheckBox hearingMapExperiment;
    private EditText rawHex;
    private ProbeLog log;
    private BroadcastReceiver bluetoothReceiver;
    private final List<BluetoothDevice> bondedDevices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        registerBluetoothEventReceiver();
        requestNeededPermissions();
        refreshDevices();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothReceiver != null) {
            try { unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
        }
    }

    private void registerBluetoothEventReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (log == null || intent == null) return;
                String action = intent.getAction();
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String label = "<unknown>";
                if (d != null) {
                    try {
                        label = safeName(d) + " / " + d.getAddress();
                    } catch (SecurityException e) {
                        label = "<bluetooth permission denied>";
                    }
                }
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    log.line("[BT broadcast] ACL_CONNECTED: " + label);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED.equals(action)) {
                    log.line("[BT broadcast] ACL_DISCONNECT_REQUESTED: " + label);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    log.line("[BT broadcast] ACL_DISCONNECTED: " + label);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bluetoothReceiver, f);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("AirPods Control Probe");
        title.setTextSize(22);
        title.setGravity(Gravity.START);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Diagnostics for AirPods AACP/ATT channels. Requires the existing LibrePods Xposed module to be active in Bluetooth.");
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        deviceSpinner = new Spinner(this);
        root.addView(deviceSpinner, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, dp(8));

        Button refresh = new Button(this);
        refresh.setText("Refresh paired devices");
        refresh.setOnClickListener(v -> refreshDevices());
        row1.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));

        Button btSettings = new Button(this);
        btSettings.setText("Bluetooth settings");
        btSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        row1.addView(btSettings, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row1, new LinearLayout.LayoutParams(-1, -2));

        probeAacp = new CheckBox(this);
        probeAacp.setText("Probe AACP PSM 4097 connect only");
        probeAacp.setChecked(true);
        root.addView(probeAacp, new LinearLayout.LayoutParams(-1, -2));

        probeAtt = new CheckBox(this);
        probeAtt.setText("Probe ATT PSM 31 and read handles 0x18 / 0x1B / 0x2A");
        probeAtt.setChecked(true);
        root.addView(probeAtt, new LinearLayout.LayoutParams(-1, -2));

        stabilityTest = new CheckBox(this);
        stabilityTest.setText("Run stability/disconnect lab: AACP init + ATT hold + repeated safe reads");
        stabilityTest.setChecked(false);
        root.addView(stabilityTest, new LinearLayout.LayoutParams(-1, -2));

        hearingWriteVerifier = new CheckBox(this);
        hearingWriteVerifier.setText("Run hearing-aid 0x2A no-op write verifier (safe read → same-value write → readback)");
        hearingWriteVerifier.setChecked(false);
        root.addView(hearingWriteVerifier, new LinearLayout.LayoutParams(-1, -2));

        hearingMethodExperiment = new CheckBox(this);
        hearingMethodExperiment.setText("EXPERIMENTAL v12: isolated 0x2A raw mutation tests (temporary writes + restore)");
        hearingMethodExperiment.setChecked(false);
        root.addView(hearingMethodExperiment, new LinearLayout.LayoutParams(-1, -2));

        hearingMapExperiment = new CheckBox(this);
        hearingMapExperiment.setText("EXPERIMENTAL v13: map ATT 0x28–0x2F + monitor AACP/ATT after 0x2A write");
        hearingMapExperiment.setChecked(false);
        root.addView(hearingMapExperiment, new LinearLayout.LayoutParams(-1, -2));

        sendRaw = new CheckBox(this);
        sendRaw.setText("Advanced: send raw hex to PSM 31 once");
        sendRaw.setChecked(false);
        root.addView(sendRaw, new LinearLayout.LayoutParams(-1, -2));

        rawHex = new EditText(this);
        rawHex.setHint("Raw hex, e.g. 0A 18 00");
        rawHex.setSingleLine(false);
        rawHex.setMinLines(1);
        rawHex.setText("0A 18 00");
        root.addView(rawHex, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, dp(8));

        Button run = new Button(this);
        run.setText("Run probe");
        run.setOnClickListener(v -> runProbe());
        row2.addView(run, new LinearLayout.LayoutParams(0, -2, 1));

        Button copy = new Button(this);
        copy.setText("Copy log");
        copy.setOnClickListener(v -> copyLog());
        row2.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row2, new LinearLayout.LayoutParams(-1, -2));

        Button share = new Button(this);
        share.setText("Share log");
        share.setOnClickListener(v -> shareLog());
        root.addView(share, new LinearLayout.LayoutParams(-1, -2));

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        log = new ProbeLog(logView);
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), 1001);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1002);
        }
    }

    private void refreshDevices() {
        bondedDevices.clear();
        List<String> labels = new ArrayList<>();
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            labels.add("Bluetooth adapter unavailable");
            deviceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            return;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (BluetoothDevice d : bonded) {
                bondedDevices.add(d);
                labels.add(safeName(d) + "  " + d.getAddress());
            }
        } catch (SecurityException e) {
            labels.add("Bluetooth permission denied. Grant permissions and refresh.");
        }
        if (labels.isEmpty()) labels.add("No paired devices found");
        ArrayAdapter<String> aa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        deviceSpinner.setAdapter(aa);
        if (log != null) log.line("Loaded " + bondedDevices.size() + " paired devices.");
    }

    private void runProbe() {
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= bondedDevices.size()) {
            Toast.makeText(this, "Select a paired AirPods device first", Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothDevice device = bondedDevices.get(pos);
        log.clear();
        new Thread(() -> new AirPodsProbe(log).probe(
                device,
                probeAacp.isChecked(),
                probeAtt.isChecked(),
                sendRaw.isChecked(),
                rawHex.getText().toString(),
                stabilityTest.isChecked(),
                hearingWriteVerifier.isChecked(),
                hearingMethodExperiment.isChecked(),
                hearingMapExperiment.isChecked()
        ), "airpods-probe-thread").start();
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? BluetoothAdapter.getDefaultAdapter() : bm.getAdapter();
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("airpods_probe_log", log.text()));
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    private void shareLog() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "AirPods Probe log");
        send.putExtra(Intent.EXTRA_TEXT, log.text());
        startActivity(Intent.createChooser(send, "Share AirPods Probe log"));
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private String safeName(BluetoothDevice d) {
        try {
            String name = d.getName();
            return name == null ? "<unnamed>" : name;
        } catch (SecurityException e) {
            return "<permission denied>";
        }
    }
}
