package dev.tomppi.airpodsprobe

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var spinner: Spinner
    private lateinit var logView: TextView
    private lateinit var runButton: Button
    private lateinit var refreshButton: Button
    private lateinit var copyButton: Button
    private val devices = mutableListOf<BluetoothDevice>()
    private var selectedDevice: BluetoothDevice? = null
    private val logBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ensurePermissionsThenLoadDevices()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "AirPods control probe v18\nAACP 0x53 init matrix + exact echo probe"
            textSize = 18f
        }
        val note = TextView(this).apply {
            text = "v18 attributes AACP 0x0053/0x0055 to the init/post-init stream, sends only exact no-op 0x53/0x55 echoes, and retests ATT notify CCCDs only after AACP is fully idle."
            textSize = 13f
        }
        spinner = Spinner(this)
        refreshButton = Button(this).apply { text = "Refresh paired devices" }
        runButton = Button(this).apply { text = "Run v18 probe"; isEnabled = false }
        copyButton = Button(this).apply { text = "Copy log" }
        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "Waiting for device..."
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(title)
        root.addView(note)
        root.addView(spinner)
        root.addView(refreshButton)
        root.addView(runButton)
        root.addView(copyButton)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        refreshButton.setOnClickListener { ensurePermissionsThenLoadDevices() }
        copyButton.setOnClickListener { copyLog() }
        runButton.setOnClickListener { startProbe() }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDevice = devices.getOrNull(position)
                runButton.isEnabled = selectedDevice != null
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedDevice = null
                runButton.isEnabled = false
            }
        }
    }

    private fun ensurePermissionsThenLoadDevices() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 42)
            return
        }
        loadDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) loadDevices()
    }

    @Suppress("MissingPermission")
    private fun loadDevices() {
        devices.clear()
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        val bonded = adapter?.bondedDevices?.toList().orEmpty()
        devices.addAll(bonded.sortedBy { it.name ?: it.address })
        val labels = devices.map { "${it.name ?: "Unknown"} / ${it.address}" }.ifEmpty { listOf("No paired Bluetooth devices found") }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        selectedDevice = devices.firstOrNull()
        runButton.isEnabled = selectedDevice != null
        appendLog("Loaded ${devices.size} paired device(s). Pick AirPods Pro, then run v18.")
    }

    private fun startProbe() {
        val device = selectedDevice ?: return
        runButton.isEnabled = false
        logBuffer.clear()
        logView.text = ""
        val sink: (String) -> Unit = { line -> appendLog(line) }
        thread(name = "airpods-probe-v18") {
            try {
                V18Probe(device, sink).run()
            } catch (t: Throwable) {
                sink("FATAL: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                ui.post { runButton.isEnabled = selectedDevice != null }
            }
        }
    }

    private fun appendLog(line: String) {
        ui.post {
            logBuffer.append(line).append('\n')
            logView.text = logBuffer.toString()
        }
    }

    private fun copyLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AirPods probe v18 log", logBuffer.toString()))
        appendLog("Log copied to clipboard.")
    }
}

private enum class WriteKind { REQUEST, COMMAND }

private data class AttRoute(
    val name: String,
    val handle: Int,
    val writeKind: WriteKind,
)

private data class CccdStep(
    val name: String,
    val handle: Int,
    val value: ByteArray,
)

private data class AacpVariant(
    val name: String,
    val packet: ByteArray,
)

private data class AacpInitVariant(
    val name: String,
    val steps: List<Pair<String, ByteArray>>,
)

private data class CaptureResult(
    val packets: List<ByteArray>,
    val first53: ByteArray?,
    val first55: ByteArray?,
)

private class V18Probe(
    private val device: BluetoothDevice,
    private val emit: (String) -> Unit,
) {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun run() {
        log("=== AirPods control probe started ===")
        log("Device: ${safeName(device)} / ${device.address}")
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.")
        log("It does not install or replace the Xposed module.")
        log("--- v18 AACP 0x53 init-matrix + exact echo probe ---")
        log("v17 showed the key correction: 0x0053/0x0055 are real, but they mostly arrive during AACP init/post-init backlog, not as a durable ATT CCCD mutation path.")
        log("v17 decoded 0x0053 as length=132, prefix=02 00 02 02, then 32 float32-le values of 0.5; 0x0055 appeared as 01 01 00 19.")
        log("v18 therefore does three things:")
        log("  1) runs an AACP init matrix to attribute which init/notification-request step creates 0x0053/0x0055/0x0017.")
        log("  2) captures 0x0053/0x0055 from the init stream reliably, then sends only exact no-op echoes and reads 0x002A after each.")
        log("  3) drains AACP fully before enabling ATT notify CCCDs, so stale post-init packets are not misattributed to 0x0022/0x002B.")

        val baseline = session("v18 baseline/map") { aacp, att ->
            drainAacp(aacp, "v18 baseline/map post-init", maxPackets = 120, timeoutMs = 5200)
            discoverHearingHandles(att)
            robustRead(att, 0x0021, "v18 baseline sibling 0x0021")
            robustRead(att, 0x0024, "v18 baseline sibling 0x0024")
            robustRead(att, 0x002A, "v18 baseline HEARING_AID_CONFIG")
        }

        if (baseline == null) {
            log("v18 aborted: could not read baseline handle 0x002A.")
            log("=== Probe finished ===")
            return
        }

        log("v18 baseline 0x2A value: ${hex(baseline)}")
        describeHearingConfig("v18 baseline 0x002A", baseline)
        log("v18 deliberately does not attempt any new ATT value mutation. Raw 0x2A/0x21/0x26/0x28 writes and 0x52-assisted commits were already ruled out by v15/v16.")

        val capture = runV18InitMatrix(baseline)
        Thread.sleep(1200)
        val echoChanged = runV18Captured53EchoAndStatus(baseline, capture)
        Thread.sleep(1200)
        runV18CccdAfterAacpIdle(baseline)

        val finalValue = session("v18 final check") { _, att ->
            robustRead(att, 0x002A, "v18 final HEARING_AID_CONFIG")
        }
        val finalEqualsBaseline = finalValue != null && finalValue.contentEquals(baseline)
        log("v18 final value equals baseline: $finalEqualsBaseline")
        if (echoChanged) {
            log("v18 result: an exact observed 0x53/0x55 echo changed 0x2A. Treat that echo section as the next implementation target, but manually verify the changed value.")
        } else {
            log("v18 result: no exact 0x53/0x55 echo changed 0x2A. The useful result is the init-step attribution and the exact decoded 0x53 vector format.")
        }
        log("v18 AACP 0x53 init-matrix + exact echo probe complete.")
        log("=== Probe finished ===")
    }

    private fun runV18InitMatrix(baseline: ByteArray): CaptureResult? {
        log("--- v18 AACP init-step attribution matrix ---")
        var best: CaptureResult? = null
        for (variant in initVariants()) {
            val result = sessionWithCustomInit("v18 init matrix ${variant.name}", variant.steps) { aacp, att, initPackets ->
                val postPackets = drainAacp(aacp, "v18 init matrix ${variant.name} deep post-init", maxPackets = 220, timeoutMs = 7500)
                val packets = initPackets + postPackets
                summarizeAacpCapture("v18 init matrix ${variant.name}", packets)
                val readback = robustRead(att, 0x002A, "v18 init matrix ${variant.name} 0x002A readback")
                log("v18 init matrix ${variant.name}: 0x2A equals baseline: ${readback != null && readback.contentEquals(baseline)}")
                CaptureResult(
                    packets = packets,
                    first53 = packets.firstOrNull { commandOf(it) == 0x0053 },
                    first55 = packets.firstOrNull { commandOf(it) == 0x0055 },
                )
            }
            if (best == null && result?.first53 != null) best = result
            Thread.sleep(900)
        }
        if (best?.first53 == null) log("v18 init matrix: no 0x0053 packet captured in any variant.")
        else log("v18 init matrix: selected first observed 0x0053/0x0055 capture for exact echo session.")
        return best
    }

    private fun runV18Captured53EchoAndStatus(baseline: ByteArray, capture: CaptureResult?): Boolean {
        log("--- v18 exact captured 0x53/0x55 echo session ---")
        val observed53 = capture?.first53
        if (observed53 == null) {
            log("v18 echo skipped: no observed AACP 0x0053 packet was captured during the init matrix.")
            return false
        }
        val observed55 = capture.first55
        var changed = false
        sessionWithCustomInit("v18 exact 0x53 echo", defaultInitSteps()) { aacp, att, initPackets ->
            val postPackets = drainAacp(aacp, "v18 exact 0x53 echo post-init settle", maxPackets = 180, timeoutMs = 5200)
            summarizeAacpCapture("v18 exact 0x53 echo local init stream", initPackets + postPackets)
            val before = robustRead(att, 0x002A, "v18 exact 0x53 echo before")
            log("v18 exact 0x53 echo: before equals baseline: ${before != null && before.contentEquals(baseline)}")

            sendAacp(aacp, "v18 exact captured 0x0053 echo", observed53)
            drainAtt(att, "v18 after exact captured 0x0053 echo", maxPackets = 24, timeoutMs = 1200)
            drainAacp(aacp, "v18 after exact captured 0x0053 echo", maxPackets = 64, timeoutMs = 2200)
            val after53 = robustRead(att, 0x002A, "v18 readback after exact captured 0x0053 echo")
            val after53Baseline = after53 != null && after53.contentEquals(baseline)
            log("v18 exact captured 0x0053 echo: readback equals baseline: $after53Baseline")
            if (after53 != null && !after53Baseline) {
                log("v18 exact captured 0x0053 echo changed readback: ${hex(after53)}")
                changed = true
            }

            if (observed55 != null) {
                sendAacp(aacp, "v18 exact captured 0x0055 echo", observed55)
                drainAtt(att, "v18 after exact captured 0x0055 echo", maxPackets = 24, timeoutMs = 1200)
                drainAacp(aacp, "v18 after exact captured 0x0055 echo", maxPackets = 64, timeoutMs = 2200)
                val after55 = robustRead(att, 0x002A, "v18 readback after exact captured 0x0055 echo")
                val after55Baseline = after55 != null && after55.contentEquals(baseline)
                log("v18 exact captured 0x0055 echo: readback equals baseline: $after55Baseline")
                if (after55 != null && !after55Baseline) {
                    log("v18 exact captured 0x0055 echo changed readback: ${hex(after55)}")
                    changed = true
                }

                sendAacp(aacp, "v18 exact captured 0x0053+0x0055 sequence - 0x53", observed53)
                sendAacp(aacp, "v18 exact captured 0x0053+0x0055 sequence - 0x55", observed55)
                drainAtt(att, "v18 after exact captured 0x53+0x55 sequence", maxPackets = 24, timeoutMs = 1200)
                drainAacp(aacp, "v18 after exact captured 0x53+0x55 sequence", maxPackets = 64, timeoutMs = 2200)
                val afterBoth = robustRead(att, 0x002A, "v18 readback after exact captured 0x53+0x55 sequence")
                val afterBothBaseline = afterBoth != null && afterBoth.contentEquals(baseline)
                log("v18 exact captured 0x53+0x55 sequence: readback equals baseline: $afterBothBaseline")
                if (afterBoth != null && !afterBothBaseline) {
                    log("v18 exact captured 0x53+0x55 sequence changed readback: ${hex(afterBoth)}")
                    changed = true
                }
            } else {
                log("v18 exact 0x0055 echo skipped: the selected capture did not include 0x0055.")
            }
        }
        log("v18 exact captured 0x53/0x55 echo changed 0x2A: $changed")
        return changed
    }

    private fun runV18CccdAfterAacpIdle(baseline: ByteArray) {
        log("--- v18 CCCD enable retest after full AACP idle ---")
        sessionWithCustomInit("v18 CCCD after idle", defaultInitSteps()) { aacp, att, initPackets ->
            val initTail = drainAacp(aacp, "v18 CCCD after idle full pre-CCCD drain", maxPackets = 240, timeoutMs = 8500)
            val idleCheck = drainAacp(aacp, "v18 CCCD after idle idle-confirmation drain", maxPackets = 32, timeoutMs = 2500)
            summarizeAacpCapture("v18 CCCD after idle pre-CCCD stream", initPackets + initTail + idleCheck)
            val before = robustRead(att, 0x002A, "v18 CCCD after idle before")
            log("v18 CCCD after idle: before equals baseline: ${before != null && before.contentEquals(baseline)}")

            for (step in listOf(
                CccdStep("0x0021 notify CCCD 0x0022", 0x0022, byteArrayOf(0x01, 0x00)),
                CccdStep("0x002A notify CCCD 0x002B", 0x002B, byteArrayOf(0x01, 0x00)),
            )) {
                log("v18 CCCD after idle: enabling ${step.name} at ${h(step.handle)} = ${hex(step.value)}")
                robustWriteRequest(att, step.handle, step.value, "v18 CCCD after idle ${step.name}")
                val attPackets = drainAtt(att, "v18 CCCD after idle after ${step.name}", maxPackets = 32, timeoutMs = 1500)
                val aacpPackets = drainAacp(aacp, "v18 CCCD after idle after ${step.name}", maxPackets = 180, timeoutMs = 7200)
                log("v18 CCCD after idle ${step.name}: ATT packets=${attPackets.size}, AACP packets=${aacpPackets.size}, saw0x53=${aacpPackets.any { commandOf(it) == 0x0053 }}, saw0x55=${aacpPackets.any { commandOf(it) == 0x0055 }}, saw0x17=${aacpPackets.any { commandOf(it) == 0x0017 }}")
            }

            val after = robustRead(att, 0x002A, "v18 CCCD after idle after")
            log("v18 CCCD after idle: after equals baseline: ${after != null && after.contentEquals(baseline)}")
        }
    }

    private fun initVariants(): List<AacpInitVariant> = listOf(
        AacpInitVariant("default flags-D7 notify-FFFFFFFF", defaultInitSteps()),
        AacpInitVariant("flags-D7 only no-0F-request", listOf(handshakeStep(), featureFlagsStep())),
        AacpInitVariant("flags-D7 notify-00000000", listOf(handshakeStep(), featureFlagsStep(), notifyRequestStep("00 00 00 00"))),
        AacpInitVariant("flags-D7 notify-01000000", listOf(handshakeStep(), featureFlagsStep(), notifyRequestStep("01 00 00 00"))),
        AacpInitVariant("flags-D7 notify-02000000", listOf(handshakeStep(), featureFlagsStep(), notifyRequestStep("02 00 00 00"))),
        AacpInitVariant("flags-D7 notify-FFFF0000", listOf(handshakeStep(), featureFlagsStep(), notifyRequestStep("FF FF 00 00"))),
    )

    private fun defaultInitSteps(): List<Pair<String, ByteArray>> = listOf(
        handshakeStep(),
        featureFlagsStep(),
        notifyRequestStep("FF FF FF FF"),
    )

    private fun handshakeStep(): Pair<String, ByteArray> =
        "handshake" to bytes("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00")

    private fun featureFlagsStep(): Pair<String, ByteArray> =
        "set feature flags D7" to bytes("04 00 04 00 4D 00 D7 00 00 00 00 00 00 00")

    private fun notifyRequestStep(maskHex: String): Pair<String, ByteArray> =
        "request notifications mask $maskHex" to concat(bytes("04 00 04 00 0F 00"), bytes(maskHex))

    private fun summarizeAacpCapture(label: String, packets: List<ByteArray>) {
        val counts = packets.mapNotNull { commandOf(it) }.groupingBy { it }.eachCount()
        val histogram = counts.entries.sortedBy { it.key }.joinToString { "0x${it.key.hex4()}=${it.value}" }
        log("$label summary: packets=${packets.size}, commands=${histogram.ifBlank { "none" }}")
        val first53 = packets.firstOrNull { commandOf(it) == 0x0053 }
        val first55 = packets.firstOrNull { commandOf(it) == 0x0055 }
        val first17 = packets.firstOrNull { commandOf(it) == 0x0017 }
        log("$label summary flags: saw0x53=${first53 != null}, saw0x55=${first55 != null}, saw0x17=${first17 != null}")
        if (first53 != null) describeAacp("$label first 0x0053", first53)
        if (first55 != null) describeAacp("$label first 0x0055", first55)
        if (first17 != null) describeAacp("$label first 0x0017", first17)
    }

    private fun describeHearingConfig(label: String, value: ByteArray) {
        val nonZero = value.withIndex().filter { it.value.u() != 0 }.joinToString(limit = 18) { "[${it.index}]=0x${it.value.u().hex2()}" }
        log("$label decode: len=${value.size}, nonZero=${nonZero.ifBlank { "none" }}")
        if (value.size >= 4) {
            log("$label decode: byte0=0x${value[0].u().hex2()}, byte1=0x${value[1].u().hex2()}, declared/body-ish word at [2..3]=${u16(value, 2)}")
        }
        if (value.isNotEmpty()) log("$label decode: last byte=0x${value.last().u().hex2()}")
    }

    private fun <T> sessionWithCustomInit(
        label: String,
        initSteps: List<Pair<String, ByteArray>>,
        block: (BluetoothSocket, BluetoothSocket, List<ByteArray>) -> T?,
    ): T? {
        var aacp: BluetoothSocket? = null
        var att: BluetoothSocket? = null
        return try {
            val aacpOpen = connectL2cap(PSM_AACP, label, preferAacp = true) ?: return null
            aacp = aacpOpen.socket
            log("$label: AACP PSM $PSM_AACP connected using ${aacpOpen.method}. Sending custom init sequence.")
            val initPackets = sendAacpInitVariant(aacp, label, initSteps)
            Thread.sleep(1000)
            val attOpen = connectL2cap(PSM_ATT, label, preferAacp = false) ?: return null
            att = attOpen.socket
            log("$label: ATT PSM $PSM_ATT connected using ${attOpen.method}.")
            block(aacp, att, initPackets)
        } catch (t: Throwable) {
            log("$label failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            closeQuietly(att)
            closeQuietly(aacp)
            log("$label sockets closed.")
        }
    }

    private fun sendAacpInitVariant(socket: BluetoothSocket, label: String, steps: List<Pair<String, ByteArray>>): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        for ((name, packet) in steps) {
            log("$label AACP init send $name: ${hex(packet)}")
            socket.outputStream.write(packet)
            socket.outputStream.flush()
            val response = readPacket(socket.inputStream, 900)
            if (response != null) {
                packets.add(response)
                log("$label AACP init $name response: ${hex(response)}")
                describeAacp("$label AACP init $name", response)
            } else {
                log("$label AACP init $name: no immediate response.")
            }
            packets.addAll(drainAacp(socket, "$label AACP init tail after $name", maxPackets = 24, timeoutMs = 900))
            Thread.sleep(250)
        }
        return packets
    }

    private fun runV18ProfileTransactionCapture(baseline: ByteArray): CaptureResult? {
        log("--- v18 full notify-triggered AACP profile transaction capture ---")
        return session("v18 full 0x53 capture") { aacp, att ->
            val packets = mutableListOf<ByteArray>()
            packets.addAll(drainAacp(aacp, "v18 full 0x53 capture post-init", maxPackets = 80, timeoutMs = 3000))
            drainAtt(att, "v18 full 0x53 capture post-init", maxPackets = 16, timeoutMs = 1000)
            val before = robustRead(att, 0x002A, "v18 full 0x53 capture baseline check")
            log("v18 full 0x53 capture: before equals baseline: ${before != null && before.contentEquals(baseline)}")

            val steps = listOf(
                CccdStep("0x0021 notify CCCD 0x0022", 0x0022, byteArrayOf(0x01, 0x00)),
                CccdStep("0x002A notify CCCD 0x002B", 0x002B, byteArrayOf(0x01, 0x00)),
            )
            for (step in steps) {
                log("v18 full 0x53 capture: enabling ${step.name} at ${h(step.handle)} = ${hex(step.value)}")
                robustWriteRequest(att, step.handle, step.value, "v18 full 0x53 capture ${step.name}")
                drainAtt(att, "v18 full 0x53 capture after ${step.name}", maxPackets = 32, timeoutMs = 1200)
                packets.addAll(drainAacp(aacp, "v18 full 0x53 capture after ${step.name}", maxPackets = 96, timeoutMs = 5200))
                Thread.sleep(500)
            }

            robustRead(att, 0x0021, "v18 full 0x53 capture sibling 0x0021 after notify setup")
            robustRead(att, 0x0024, "v18 full 0x53 capture sibling 0x0024 after notify setup")
            val after = robustRead(att, 0x002A, "v18 full 0x53 capture final 0x2A")
            log("v18 full 0x53 capture: final 0x2A equals baseline: ${after != null && after.contentEquals(baseline)}")

            val first53 = packets.firstOrNull { commandOf(it) == 0x0053 }
            val first55 = packets.firstOrNull { commandOf(it) == 0x0055 }
            log("v18 full 0x53 capture summary: packets=${packets.size}, saw0x53=${first53 != null}, saw0x55=${first55 != null}, saw0x17=${packets.any { commandOf(it) == 0x0017 }}")
            CaptureResult(packets.toList(), first53, first55)
        }
    }

    private fun runV18Aacp53EchoAndStatus(baseline: ByteArray, capture: CaptureResult?): Boolean {
        log("--- v18 exact observed 0x53/0x55 echo no-op session ---")
        if (capture?.first53 == null) {
            log("v18 echo skipped: no observed AACP 0x0053 packet was captured.")
            return false
        }
        var changed = false
        session("v18 0x53 echo") { aacp, att ->
            drainAacp(aacp, "v18 0x53 echo post-init", maxPackets = 64, timeoutMs = 2600)
            enableNotifyPairOnly(att, aacp, "v18 0x53 echo")
            val before = robustRead(att, 0x002A, "v18 0x53 echo before")
            log("v18 0x53 echo: before equals baseline: ${before != null && before.contentEquals(baseline)}")

            sendAacp(aacp, "v18 exact observed 0x0053 echo", capture.first53)
            drainAtt(att, "v18 after exact 0x0053 echo", maxPackets = 24, timeoutMs = 1200)
            val after53 = robustRead(att, 0x002A, "v18 readback after exact 0x0053 echo")
            val after53Baseline = after53 != null && after53.contentEquals(baseline)
            log("v18 exact 0x0053 echo: readback equals baseline: $after53Baseline")
            if (after53 != null && !after53Baseline) {
                log("v18 exact 0x0053 echo changed readback: ${hex(after53)}")
                changed = true
            }

            val status = capture.first55
            if (status != null) {
                sendAacp(aacp, "v18 exact observed 0x0055 echo", status)
                drainAtt(att, "v18 after exact 0x0055 echo", maxPackets = 24, timeoutMs = 1200)
                val after55 = robustRead(att, 0x002A, "v18 readback after exact 0x0055 echo")
                val after55Baseline = after55 != null && after55.contentEquals(baseline)
                log("v18 exact 0x0055 echo: readback equals baseline: $after55Baseline")
                if (after55 != null && !after55Baseline) {
                    log("v18 exact 0x0055 echo changed readback: ${hex(after55)}")
                    changed = true
                }

                sendAacp(aacp, "v18 exact observed 0x0053 then 0x0055 sequence - 0x53", capture.first53)
                sendAacp(aacp, "v18 exact observed 0x0053 then 0x0055 sequence - 0x55", status)
                drainAtt(att, "v18 after exact 0x53+0x55 sequence", maxPackets = 24, timeoutMs = 1200)
                val afterBoth = robustRead(att, 0x002A, "v18 readback after exact 0x53+0x55 sequence")
                val afterBothBaseline = afterBoth != null && afterBoth.contentEquals(baseline)
                log("v18 exact 0x53+0x55 sequence: readback equals baseline: $afterBothBaseline")
                if (afterBoth != null && !afterBothBaseline) {
                    log("v18 exact 0x53+0x55 sequence changed readback: ${hex(afterBoth)}")
                    changed = true
                }
            } else {
                log("v18 0x55 echo skipped: no observed AACP 0x0055 packet was captured.")
            }
        }
        log("v18 exact 0x53/0x55 echo no-op changed 0x2A: $changed")
        return changed
    }

    private fun runV18SplitNotifyComparison(baseline: ByteArray) {
        log("--- v18 isolated 0x0022-vs-0x002B notify comparison ---")
        val isolated = listOf(
            CccdStep("only 0x0021 notify CCCD 0x0022", 0x0022, byteArrayOf(0x01, 0x00)),
            CccdStep("only 0x002A notify CCCD 0x002B", 0x002B, byteArrayOf(0x01, 0x00)),
        )
        for (step in isolated) {
            session("v18 ${step.name}") { aacp, att ->
                drainAacp(aacp, "v18 ${step.name} post-init", maxPackets = 80, timeoutMs = 3000)
                drainAtt(att, "v18 ${step.name} post-init", maxPackets = 16, timeoutMs = 1000)
                val before = robustRead(att, 0x002A, "v18 ${step.name} before")
                log("v18 ${step.name}: before equals baseline: ${before != null && before.contentEquals(baseline)}")
                robustWriteRequest(att, step.handle, step.value, "v18 ${step.name}")
                val attPackets = drainAtt(att, "v18 ${step.name} after enable", maxPackets = 32, timeoutMs = 1500)
                val aacpPackets = drainAacp(aacp, "v18 ${step.name} after enable", maxPackets = 120, timeoutMs = 6500)
                val after = robustRead(att, 0x002A, "v18 ${step.name} after")
                log("v18 ${step.name}: after equals baseline: ${after != null && after.contentEquals(baseline)}")
                log("v18 ${step.name} summary: ATT packets=${attPackets.size}, AACP packets=${aacpPackets.size}, saw0x53=${aacpPackets.any { commandOf(it) == 0x0053 }}, saw0x55=${aacpPackets.any { commandOf(it) == 0x0055 }}, saw0x17=${aacpPackets.any { commandOf(it) == 0x0017 }}")
            }
            Thread.sleep(1000)
        }
    }

    private fun enableNotifyPairOnly(att: BluetoothSocket, aacp: BluetoothSocket, label: String) {
        log("$label: enabling notify descriptors 0x0022 and 0x002B only; v16 showed these trigger the 0x53/profile stream.")
        for (step in listOf(
            CccdStep("0x0021 notify CCCD 0x0022", 0x0022, byteArrayOf(0x01, 0x00)),
            CccdStep("0x002A notify CCCD 0x002B", 0x002B, byteArrayOf(0x01, 0x00)),
        )) {
            log("$label: enabling ${step.name} at ${h(step.handle)} = ${hex(step.value)}")
            robustWriteRequest(att, step.handle, step.value, "$label enable ${step.name}")
            drainAtt(att, "$label after enabling ${step.name}", maxPackets = 20, timeoutMs = 800)
            drainAacp(aacp, "$label after enabling ${step.name}", maxPackets = 64, timeoutMs = 2800)
        }
    }

    private fun runCccdAttribution(baseline: ByteArray) {
        log("--- v18 CCCD attribution session: find what triggers AACP 0x0052 ---")
        session("v18 CCCD attribution") { aacp, att ->
            drainAacp(aacp, "v18 CCCD attribution post-init", timeoutMs = 1500)
            drainAtt(att, "v18 CCCD attribution post-init")
            val before = robustRead(att, 0x002A, "v18 CCCD attribution baseline check")
            log("v18 CCCD attribution: before equals baseline: ${before != null && before.contentEquals(baseline)}")

            for (step in cccdSteps()) {
                log("v18 CCCD attribution: enabling ${step.name} at ${h(step.handle)} = ${hex(step.value)}")
                robustWriteRequest(att, step.handle, step.value, "v18 CCCD attribution ${step.name}")
                drainAtt(att, "v18 CCCD attribution after ${step.name}", timeoutMs = 600)
                val aacpPackets = drainAacp(aacp, "v18 CCCD attribution after ${step.name}", timeoutMs = 2200)
                val saw52 = aacpPackets.any { it.size >= 6 && u16(it, 4) == 0x0052 }
                log("v18 CCCD attribution: ${step.name} produced AACP 0x0052: $saw52")
                Thread.sleep(350)
            }

            val after = robustRead(att, 0x002A, "v18 CCCD attribution final 0x2A")
            log("v18 CCCD attribution: final 0x2A equals baseline: ${after != null && after.contentEquals(baseline)}")
        }
    }

    private fun runAacp52Variants(baseline: ByteArray, target: ByteArray) {
        log("--- v18 AACP 0x0052 direct status/query variants ---")
        session("v18 AACP 0x52 variants") { aacp, att ->
            drainAacp(aacp, "v18 AACP 0x52 variants post-init", timeoutMs = 1500)
            enableAllNotifyIndicate(att, aacp, "v18 AACP 0x52 variants")
            val before = robustRead(att, 0x002A, "v18 AACP 0x52 variants before")
            log("v18 AACP 0x52 variants: before equals baseline: ${before != null && before.contentEquals(baseline)}")

            for (variant in aacpVariants()) {
                sendAacp(aacp, variant.name, variant.packet)
                drainAtt(att, "v18 AACP 0x52 variants after ${variant.name}", timeoutMs = 700)
                val after = robustRead(att, 0x002A, "v18 AACP 0x52 variants readback after ${variant.name}")
                val equalsTarget = after != null && after.contentEquals(target)
                val equalsBaseline = after != null && after.contentEquals(baseline)
                log("v18 AACP 0x52 variants: ${variant.name} readback equals mutated: $equalsTarget, equals baseline: $equalsBaseline")
                if (after != null && !equalsBaseline) log("v18 AACP 0x52 variants: ${variant.name} readback value: ${hex(after)}")
                Thread.sleep(600)
            }

            // Try to leave any 0x52 status bit in the lower/false-looking state observed by the variant set.
            sendAacp(aacp, "v18 AACP 0x52 variants final observed-status-00 restore", bytes("04 00 04 00 52 00 03 00 02 01 00"))
            robustWriteCommand(att, 0x002A, baseline, "v18 AACP 0x52 variants direct 0x2A baseline fallback")
        }
    }

    private fun runAacpCommitAfterAttWrites(baseline: ByteArray, target: ByteArray): Boolean {
        log("--- v18 AACP 0x0052 commit-after-ATT-staged-write session ---")
        var anyHit = false
        session("v18 AACP commit after ATT") { aacp, att ->
            drainAacp(aacp, "v18 AACP commit after ATT post-init", timeoutMs = 1500)
            enableAllNotifyIndicate(att, aacp, "v18 AACP commit after ATT")
            val before = robustRead(att, 0x002A, "v18 AACP commit before routes")
            log("v18 AACP commit after ATT: before equals baseline: ${before != null && before.contentEquals(baseline)}")

            for (route in commitRoutes()) {
                log("v18 AACP commit route: ${route.name} staging target on ${h(route.handle)} using ${route.writeKind}")
                when (route.writeKind) {
                    WriteKind.REQUEST -> robustWriteRequest(att, route.handle, target, "v18 AACP commit stage ${route.name}")
                    WriteKind.COMMAND -> robustWriteCommand(att, route.handle, target, "v18 AACP commit stage ${route.name}")
                }
                drainAtt(att, "v18 AACP commit ${route.name} after staging", timeoutMs = 700)
                drainAacp(aacp, "v18 AACP commit ${route.name} after staging", timeoutMs = 900)

                for (commit in commitVariants()) {
                    sendAacp(aacp, "v18 AACP commit ${route.name} ${commit.name}", commit.packet)
                    drainAtt(att, "v18 AACP commit ${route.name} after ${commit.name}", timeoutMs = 800)
                    val readback = robustRead(att, 0x002A, "v18 AACP commit ${route.name} readback after ${commit.name}")
                    val equalsTarget = readback != null && readback.contentEquals(target)
                    val equalsBaseline = readback != null && readback.contentEquals(baseline)
                    log("v18 AACP commit ${route.name} ${commit.name}: readback equals mutated: $equalsTarget, equals baseline: $equalsBaseline")
                    if (readback != null && !equalsBaseline) log("v18 AACP commit ${route.name} ${commit.name}: readback value: ${hex(readback)}")
                    if (equalsTarget) anyHit = true
                    Thread.sleep(500)
                }

                log("v18 AACP commit route: ${route.name} restore attempt.")
                when (route.writeKind) {
                    WriteKind.REQUEST -> robustWriteRequest(att, route.handle, baseline, "v18 AACP commit restore ${route.name}")
                    WriteKind.COMMAND -> robustWriteCommand(att, route.handle, baseline, "v18 AACP commit restore ${route.name}")
                }
                sendAacp(aacp, "v18 AACP commit ${route.name} restore observed-status-00", bytes("04 00 04 00 52 00 03 00 02 01 00"))
                robustWriteCommand(att, 0x002A, baseline, "v18 AACP commit ${route.name} direct 0x2A baseline fallback")
                Thread.sleep(900)
            }
        }
        log("v18 AACP commit-after-ATT overall HIT: $anyHit")
        return anyHit
    }

    private fun cccdSteps(): List<CccdStep> = listOf(
        CccdStep("0x0021 notify CCCD 0x0022", 0x0022, byteArrayOf(0x01, 0x00)),
        CccdStep("0x002A notify CCCD 0x002B", 0x002B, byteArrayOf(0x01, 0x00)),
        CccdStep("0x002E indicate CCCD 0x002F", 0x002F, byteArrayOf(0x02, 0x00)),
        CccdStep("0x0031 indicate CCCD 0x0032", 0x0032, byteArrayOf(0x02, 0x00)),
        CccdStep("0x0034 indicate CCCD 0x0035", 0x0035, byteArrayOf(0x02, 0x00)),
        CccdStep("0x0037 indicate CCCD 0x0038", 0x0038, byteArrayOf(0x02, 0x00)),
    )

    private fun aacpVariants(): List<AacpVariant> = listOf(
        AacpVariant("0x52 short query/no payload", bytes("04 00 04 00 52 00")),
        AacpVariant("0x52 observed-status-01", bytes("04 00 04 00 52 00 03 00 02 01 01")),
        AacpVariant("0x52 observed-status-00", bytes("04 00 04 00 52 00 03 00 02 01 00")),
        AacpVariant("0x52 observed-selector-00-01", bytes("04 00 04 00 52 00 03 00 02 00 01")),
        AacpVariant("0x52 observed-selector-00-00", bytes("04 00 04 00 52 00 03 00 02 00 00")),
    )

    private fun commitVariants(): List<AacpVariant> = listOf(
        AacpVariant("commit observed-status-01", bytes("04 00 04 00 52 00 03 00 02 01 01")),
        AacpVariant("commit observed-status-00", bytes("04 00 04 00 52 00 03 00 02 01 00")),
    )

    private fun commitRoutes(): List<AttRoute> = listOf(
        AttRoute("direct 0x002A write-command", 0x002A, WriteKind.COMMAND),
        AttRoute("sibling 0x0026 write-command", 0x0026, WriteKind.COMMAND),
        AttRoute("sibling 0x0021 write-command", 0x0021, WriteKind.COMMAND),
        AttRoute("sibling 0x0028 write-request", 0x0028, WriteKind.REQUEST),
    )

    private fun enableAllNotifyIndicate(att: BluetoothSocket, aacp: BluetoothSocket, label: String) {
        log("$label: enabling all known notify/indicate descriptors: 0x0022, 0x002B, 0x002F, 0x0032, 0x0035, 0x0038")
        for (step in cccdSteps()) {
            log("$label: enabling ${step.name} at ${h(step.handle)} = ${hex(step.value)}")
            robustWriteRequest(att, step.handle, step.value, "$label enable ${step.name}")
            drainAtt(att, "$label after enabling ${step.name}", timeoutMs = 350)
            drainAacp(aacp, "$label after enabling ${step.name}", timeoutMs = 700)
        }
        drainAtt(att, "$label after all descriptor setup", timeoutMs = 800)
        drainAacp(aacp, "$label after all descriptor setup", timeoutMs = 1400)
    }

    private fun controlledMutation(baseline: ByteArray): ByteArray {
        val target = baseline.copyOf()
        if (target.size > 4) target[4] = 0x01
        else if (target.isNotEmpty()) target[target.lastIndex] = (target[target.lastIndex].toInt() xor 0x01).toByte()
        return target
    }

    private fun <T> session(label: String, block: (BluetoothSocket, BluetoothSocket) -> T?): T? {
        var aacp: BluetoothSocket? = null
        var att: BluetoothSocket? = null
        return try {
            val aacpOpen = connectL2cap(PSM_AACP, label, preferAacp = true) ?: return null
            aacp = aacpOpen.socket
            log("$label: AACP PSM $PSM_AACP connected using ${aacpOpen.method}. Sending init sequence.")
            sendAacpInit(aacp)
            Thread.sleep(1000)
            val attOpen = connectL2cap(PSM_ATT, label, preferAacp = false) ?: return null
            att = attOpen.socket
            log("$label: ATT PSM $PSM_ATT connected using ${attOpen.method}.")
            block(aacp, att)
        } catch (t: Throwable) {
            log("$label failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            closeQuietly(att)
            closeQuietly(aacp)
            log("$label sockets closed.")
        }
    }

    private fun sendAacpInit(socket: BluetoothSocket) {
        val steps = listOf(
            "handshake" to bytes("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00"),
            "set feature flags" to bytes("04 00 04 00 4D 00 D7 00 00 00 00 00 00 00"),
            "request notifications" to bytes("04 00 04 00 0F 00 FF FF FF FF"),
        )
        for ((name, packet) in steps) {
            log("AACP init send $name: ${hex(packet)}")
            socket.outputStream.write(packet)
            socket.outputStream.flush()
            val response = readPacket(socket.inputStream, 700)
            if (response != null) {
                log("AACP init $name response: ${hex(response)}")
                describeAacp("AACP init $name", response)
            } else {
                log("AACP init $name: no immediate response.")
            }
            Thread.sleep(250)
        }
        repeat(2) { index ->
            val extra = readPacket(socket.inputStream, 350)
            if (extra != null) {
                log("AACP extra response ${index + 1}: ${hex(extra)}")
                describeAacp("AACP extra response ${index + 1}", extra)
            }
        }
    }

    private fun sendAacp(socket: BluetoothSocket, label: String, packet: ByteArray): List<ByteArray> {
        log("AACP send $label: ${hex(packet)}")
        try {
            socket.outputStream.write(packet)
            socket.outputStream.flush()
        } catch (t: Throwable) {
            log("AACP send $label failed: ${t.javaClass.simpleName}: ${t.message}")
            return emptyList()
        }
        val packets = mutableListOf<ByteArray>()
        val first = readPacket(socket.inputStream, 900)
        if (first != null) {
            packets.add(first)
            log("AACP response to $label packet 1: ${hex(first)}")
            describeAacp("AACP response to $label packet 1", first)
        } else {
            log("AACP response to $label: no immediate packet.")
        }
        packets.addAll(drainAacp(socket, "AACP response tail for $label", maxPackets = 48, timeoutMs = 1800))
        return packets
    }

    private fun discoverHearingHandles(att: BluetoothSocket) {
        log("--- v18 ATT discovery around hearing handles 0x0020–0x0038 ---")
        findInfoPaged(att, 0x0020, 0x0038)
        readByTypeCharacteristicDeclarations(att, 0x0020, 0x0038)
        log("v18 expected map from v14/v15:")
        log("  0x0021 read/write-no-response/notify, CCCD 0x0022")
        log("  0x0026 write-no-response")
        log("  0x0028 write-request")
        log("  0x002A read/write-no-response/notify, CCCD 0x002B")
        log("  0x002E/0x0031/0x0034/0x0037 write+indicate with CCCDs 0x002F/0x0032/0x0035/0x0038")
    }

    private fun findInfoPaged(att: BluetoothSocket, start: Int, end: Int) {
        var cursor = start
        while (cursor <= end) {
            val req = byteArrayOf(0x04, lo(cursor), hi(cursor), lo(end), hi(end))
            log("ATT Find Information page request ${h(cursor)}–${h(end)}: ${hex(req)}")
            att.outputStream.write(req)
            att.outputStream.flush()
            val rsp = readPacket(att.inputStream, 900)
            if (rsp == null) {
                log("ATT Find Information page ${h(cursor)}: no response")
                break
            }
            log("ATT Find Information page response: ${hex(rsp)}")
            if ((rsp[0].u() == 0x01) || (rsp[0].u() != 0x05) || rsp.size < 4) break
            val format = rsp[1].u()
            var lastHandle = cursor
            if (format == 0x01) {
                var i = 2
                while (i + 3 < rsp.size) {
                    val handle = u16(rsp, i)
                    val uuid = u16(rsp, i + 2)
                    log("  handle ${h(handle)} uuid 0x${uuid.toString(16).padStart(4, '0').uppercase()} ${knownUuid16(uuid)}")
                    lastHandle = handle
                    i += 4
                }
            } else if (format == 0x02) {
                var i = 2
                while (i + 17 < rsp.size) {
                    val handle = u16(rsp, i)
                    val uuid = rsp.copyOfRange(i + 2, i + 18)
                    log("  handle ${h(handle)} uuid128-le ${hex(uuid)}")
                    lastHandle = handle
                    i += 18
                }
            }
            if (lastHandle < cursor) break
            cursor = lastHandle + 1
        }
    }

    private fun readByTypeCharacteristicDeclarations(att: BluetoothSocket, start: Int, end: Int) {
        val req = byteArrayOf(0x08, lo(start), hi(start), lo(end), hi(end), 0x03, 0x28)
        log("ATT Read By Type characteristic declarations ${h(start)}–${h(end)} request: ${hex(req)}")
        att.outputStream.write(req)
        att.outputStream.flush()
        val rsp = readPacket(att.inputStream, 1200)
        if (rsp == null) {
            log("ATT Read By Type: no response")
            return
        }
        log("ATT Read By Type response: ${hex(rsp)}")
        if (rsp[0].u() != 0x09 || rsp.size < 2) return
        val entryLen = rsp[1].u()
        var i = 2
        while (i + entryLen <= rsp.size && entryLen >= 7) {
            val declHandle = u16(rsp, i)
            val props = rsp[i + 2].u()
            val valueHandle = u16(rsp, i + 3)
            val uuid = rsp.copyOfRange(i + 5, i + entryLen)
            log("  char decl ${h(declHandle)} props 0x${props.toString(16).padStart(2, '0').uppercase()} valueHandle ${h(valueHandle)} uuid ${if (uuid.size == 2) "0x${u16(uuid, 0).toString(16).padStart(4, '0').uppercase()}" else "128-le ${hex(uuid)}"} ${propsText(props)}")
            i += entryLen
        }
    }

    private fun robustRead(socket: BluetoothSocket, handle: Int, label: String, timeoutMs: Long = 2600): ByteArray? {
        val req = byteArrayOf(0x0A, lo(handle), hi(handle))
        log("ATT robust read $label handle ${h(handle)} request: ${hex(req)}")
        socket.outputStream.write(req)
        socket.outputStream.flush()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val packet = readPacket(socket.inputStream, 250) ?: continue
            val op = packet[0].u()
            log("ATT robust read ${h(handle)} candidate opcode 0x${op.hex2()}: ${hex(packet)}")
            when (op) {
                0x0B -> {
                    val value = packet.copyOfRange(1, packet.size)
                    log("ATT robust read ${h(handle)} parsed value: ${hex(value)}")
                    return value
                }
                0x01 -> {
                    if (packet.size >= 5 && packet[1].u() == 0x0A && u16(packet, 2) == handle) {
                        log("ATT robust read ${h(handle)} error response: ${hex(packet)}")
                        return null
                    }
                    log("ATT robust read ignored unrelated ATT error.")
                }
                0x13 -> log("ATT robust read ignored stale Write Response 0x13.")
                0x1B -> log("ATT robust read observed notification while waiting for read: ${hex(packet)}")
                0x1D -> {
                    log("ATT robust read observed indication while waiting for read: ${hex(packet)}; sending confirmation 0x1E")
                    confirmIndication(socket)
                }
                else -> log("ATT robust read ignored unrelated packet while waiting for 0x0B.")
            }
        }
        log("ATT robust read ${h(handle)} timed out.")
        return null
    }

    private fun robustWriteRequest(socket: BluetoothSocket, handle: Int, value: ByteArray, label: String): Boolean {
        val req = concat(byteArrayOf(0x12, lo(handle), hi(handle)), value)
        log("ATT robust write-request $label handle ${h(handle)} request: ${hex(req)}")
        socket.outputStream.write(req)
        socket.outputStream.flush()
        val deadline = SystemClock.elapsedRealtime() + 2600
        while (SystemClock.elapsedRealtime() < deadline) {
            val packet = readPacket(socket.inputStream, 250) ?: continue
            val op = packet[0].u()
            log("ATT robust write ${h(handle)} candidate opcode 0x${op.hex2()}: ${hex(packet)}")
            when (op) {
                0x13 -> return true
                0x01 -> {
                    if (packet.size >= 5 && packet[1].u() == 0x12 && u16(packet, 2) == handle) {
                        log("ATT write-request ${h(handle)} error response: ${hex(packet)}")
                        return false
                    }
                    log("ATT robust write ignored unrelated error.")
                }
                0x1D -> {
                    log("ATT robust write observed indication while waiting for 0x13/error: ${hex(packet)}; sending confirmation 0x1E")
                    confirmIndication(socket)
                }
                0x0B, 0x1B -> log("ATT robust write ignored unrelated packet while waiting for 0x13/error.")
                else -> log("ATT robust write ignored unrelated packet.")
            }
        }
        log("ATT write-request ${h(handle)} timed out waiting for 0x13/error.")
        return false
    }

    private fun robustWriteCommand(socket: BluetoothSocket, handle: Int, value: ByteArray, label: String): Boolean {
        val req = concat(byteArrayOf(0x52, lo(handle), hi(handle)), value)
        log("ATT robust write-command $label handle ${h(handle)} request: ${hex(req)}")
        socket.outputStream.write(req)
        socket.outputStream.flush()
        log("ATT robust write-command sent; no immediate ATT response expected.")
        return true
    }

    private fun drainAtt(socket: BluetoothSocket, label: String, maxPackets: Int = 8, timeoutMs: Long = 900): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (packets.size < maxPackets && SystemClock.elapsedRealtime() < deadline) {
            val p = readPacket(socket.inputStream, 120) ?: continue
            packets.add(p)
            log("$label: drained ATT packet ${packets.size} opcode 0x${p[0].u().hex2()}: ${hex(p)}")
            if (p[0].u() == 0x1D) {
                log("$label: ATT indication observed; sending Handle Value Confirmation 0x1E")
                confirmIndication(socket)
            }
        }
        if (packets.isEmpty()) log("$label: no unsolicited/stale ATT packets.")
        else log("$label: drained ${packets.size} ATT packet(s).")
        return packets
    }

    private fun drainAacp(socket: BluetoothSocket, label: String, maxPackets: Int = 12, timeoutMs: Long = 900): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (packets.size < maxPackets && SystemClock.elapsedRealtime() < deadline) {
            val p = readPacket(socket.inputStream, 120) ?: continue
            packets.add(p)
            log("AACP drain $label packet ${packets.size}: ${hex(p)}")
            describeAacp("AACP drain $label packet ${packets.size}", p)
        }
        if (packets.isEmpty()) log("AACP drain $label: no packets.")
        return packets
    }

    private fun confirmIndication(socket: BluetoothSocket) {
        try {
            socket.outputStream.write(byteArrayOf(0x1E))
            socket.outputStream.flush()
        } catch (t: Throwable) {
            log("ATT indication confirmation failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun describeAacp(label: String, p: ByteArray) {
        if (p.size >= 6) {
            val header0 = u16(p, 0)
            val header1 = u16(p, 2)
            val command = u16(p, 4)
            log("  $label header: type/service? 0x${header0.hex4()} / 0x${header1.hex4()}")
            log("  $label message/command? 0x${command.hex4()}${aacpName(command)}")
            if (command == 0x0052) {
                val payload = p.copyOfRange(6, p.size)
                log("  $label AACP 0x0052 payload (${payload.size} byte): ${hex(payload)}")
                if (payload.size >= 2) log("  $label AACP 0x0052 payload[0..1] as u16-le: 0x${u16(payload, 0).hex4()}")
                if (payload.size >= 5) log("  $label AACP 0x0052 payload bytes heuristic: lenOrSelector=${payload[0].u()} ${payload[1].u()}, group=${payload[2].u()}, valueA=${payload[3].u()}, valueB=${payload[4].u()}")
            }
            if (command == 0x0053) {
                val payload = payloadOf(p)
                log("  $label AACP 0x0053 payload (${payload.size} byte): ${hex(payload)}")
                if (payload.size >= 2) log("  $label AACP 0x0053 declared/profile length word: ${u16(payload, 0)}")
                if (payload.size >= 6) {
                    val prefix = payload.copyOfRange(2, min(payload.size, 6))
                    log("  $label AACP 0x0053 prefix after length: ${hex(prefix)}")
                    val values = floatsLe(payload, 6)
                    if (values.isNotEmpty()) {
                        val shown = values.take(12).joinToString { String.format(Locale.US, "%.6f", it) }
                        val minVal = values.minOrNull()
                        val maxVal = values.maxOrNull()
                        log("  $label AACP 0x0053 float32-le values count=${values.size}, first=${shown}, min=$minVal, max=$maxVal")
                    }
                }
            }
            if (command == 0x0055) {
                val payload = payloadOf(p)
                log("  $label AACP 0x0055 payload (${payload.size} byte): ${hex(payload)}")
                if (payload.isNotEmpty()) log("  $label AACP 0x0055 bytes decimal: ${payload.joinToString { it.u().toString() }}")
            }
            if (command == 0x0017 || command == 0x001D) {
                val tokens = asciiTokens(payloadOf(p)).take(18)
                if (tokens.isNotEmpty()) log("  $label ASCII tokens: ${tokens.joinToString(" | ")}")
            }
        }
        val interesting = mapOf(
            0x22 to "hearingAidCapability?",
            0x31 to "hearingAidV2Capability?",
            0xC0 to "hearingAidCapability 0xC0?",
            0xD0 to "hearingTestCapability?",
            0x28 to "hearingProtectionPPECapability?",
            0x26 to "heartRateMonitorCapability?",
            0x52 to "AACP 0x52 byte?",
        )
        val hits = mutableListOf<String>()
        for (i in p.indices) {
            val name = interesting[p[i].u()] ?: continue
            hits.add("$name at offset $i")
        }
        if (hits.isNotEmpty()) {
            log("  $label heuristic capability/status scan:")
            hits.take(12).forEach { log("    found $it") }
        }
    }

    private fun commandOf(packet: ByteArray): Int? = if (packet.size >= 6) u16(packet, 4) else null

    private fun payloadOf(packet: ByteArray): ByteArray = if (packet.size > 6) packet.copyOfRange(6, packet.size) else ByteArray(0)

    private fun floatsLe(bytes: ByteArray, offset: Int): List<Float> {
        if (offset >= bytes.size) return emptyList()
        val count = (bytes.size - offset) / 4
        val out = ArrayList<Float>(count)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) out.add(bb.getFloat(offset + i * 4))
        return out
    }

    private fun asciiTokens(bytes: ByteArray): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= 4) tokens.add(current.toString())
            current.clear()
        }
        for (b in bytes) {
            val c = b.u()
            if (c in 0x20..0x7E) current.append(c.toChar()) else flush()
        }
        flush()
        return tokens
    }

    private fun connectL2cap(psm: Int, label: String, preferAacp: Boolean): OpenedSocket? {
        val methods = if (preferAacp) {
            listOf("createL2capSocket", "createInsecureL2capSocket", "createL2capChannel", "createInsecureL2capChannel")
        } else {
            listOf("createInsecureL2capSocket", "createL2capSocket", "createInsecureL2capChannel", "createL2capChannel")
        }
        for (method in methods) {
            try {
                log("$label: trying $method($psm).")
                val reflected = device.javaClass.getMethod(method, Int::class.javaPrimitiveType)
                val socket = reflected.invoke(device, psm) as BluetoothSocket
                val error = AtomicReference<Throwable?>(null)
                val worker = thread(start = true) {
                    try { socket.connect() } catch (t: Throwable) { error.set(t) }
                }
                worker.join(6500)
                if (worker.isAlive) {
                    log("$label: connect() timed out after 6500 ms; closing socket.")
                    closeQuietly(socket)
                    continue
                }
                val thrown = error.get()
                if (thrown != null) {
                    log("$label: connect() failed using $method: ${thrown.javaClass.simpleName}: ${thrown.message}")
                    closeQuietly(socket)
                    continue
                }
                return OpenedSocket(socket, method)
            } catch (e: NoSuchMethodException) {
                log("$label: $method($psm) not available on this Android build.")
            } catch (t: Throwable) {
                log("$label: $method($psm) failed before connect: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        log("$label: PSM $psm did not connect.")
        return null
    }

    private fun readPacket(input: InputStream, timeoutMs: Long): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val available = try { input.available() } catch (_: Throwable) { 0 }
            if (available > 0) {
                Thread.sleep(12)
                val toRead = maxOf(available, input.available())
                val buf = ByteArray(toRead)
                val n = input.read(buf)
                if (n > 0) return buf.copyOf(n)
            }
            Thread.sleep(8)
        }
        return null
    }

    private fun log(message: String) {
        emit("${timeFormat.format(Date())}  $message")
    }

    @Suppress("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = try { device.name ?: "Unknown" } catch (_: SecurityException) { "Unknown" }

    private fun closeQuietly(socket: BluetoothSocket?) {
        try { socket?.close() } catch (_: Throwable) {}
    }

    private data class OpenedSocket(val socket: BluetoothSocket, val method: String)

    companion object {
        private const val PSM_AACP = 4097
        private const val PSM_ATT = 31
    }
}

private fun Byte.u(): Int = toInt() and 0xFF
private fun Int.hex2(): String = toString(16).padStart(2, '0').uppercase(Locale.US)
private fun Int.hex4(): String = toString(16).padStart(4, '0').uppercase(Locale.US)
private fun h(v: Int): String = "0x${v.hex4()}"
private fun lo(v: Int): Byte = (v and 0xFF).toByte()
private fun hi(v: Int): Byte = ((v ushr 8) and 0xFF).toByte()
private fun u16(b: ByteArray, offset: Int): Int = b[offset].u() or (b[offset + 1].u() shl 8)

private fun concat(vararg chunks: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    for (c in chunks) out.write(c)
    return out.toByteArray()
}

private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { it.u().hex2() }

private fun bytes(hex: String): ByteArray {
    val parts = hex.trim().split(Regex("\\s+"))
    return parts.filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}

private fun propsText(props: Int): String {
    val names = mutableListOf<String>()
    if ((props and 0x02) != 0) names.add("read")
    if ((props and 0x04) != 0) names.add("write-no-response")
    if ((props and 0x08) != 0) names.add("write")
    if ((props and 0x10) != 0) names.add("notify")
    if ((props and 0x20) != 0) names.add("indicate")
    return names.joinToString(prefix = "[", postfix = "]")
}

private fun knownUuid16(uuid: Int): String = when (uuid) {
    0x2800 -> "(Primary Service)"
    0x2803 -> "(Characteristic Declaration)"
    0x2902 -> "(Client Characteristic Configuration / CCCD)"
    else -> ""
}

private fun aacpName(command: Int): String = when (command) {
    0x0002 -> " (Capabilities message?)"
    0x0004 -> " (AACP message/service 4?)"
    0x0006 -> " (Ack/keepalive/status?)"
    0x0008 -> " (Init/status?)"
    0x0009 -> " (Capability/status entry?)"
    0x000C -> " (Device status?)"
    0x000E -> " (Device status?)"
    0x001D -> " (Accessory info?)"
    0x002B -> " (Feature flags response?)"
    0x002E -> " (Notification registration/status?)"
    0x004E -> " (Observed v13 indication-side status?)"
    0x0052 -> " (AACP 0x52 status/commit clue from v15/v16; not a hit)"
    0x0053 -> " (AACP 0x53 profile/vector candidate)"
    0x0055 -> " (AACP 0x55 status/ack candidate)"
    else -> ""
}
