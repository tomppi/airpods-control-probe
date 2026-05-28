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
            text = "AirPods control probe v16\nGitHub-runner Android repo build"
            textSize = 18f
        }
        val note = TextView(this).apply {
            text = "v16 follows the v15 result: raw ATT writes did not persist. It focuses on AACP message 0x0052 attribution and AACP-assisted commit attempts after staged ATT writes."
            textSize = 13f
        }
        spinner = Spinner(this)
        refreshButton = Button(this).apply { text = "Refresh paired devices" }
        runButton = Button(this).apply { text = "Run v16 probe"; isEnabled = false }
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
        appendLog("Loaded ${devices.size} paired device(s). Pick AirPods Pro, then run v16.")
    }

    private fun startProbe() {
        val device = selectedDevice ?: return
        runButton.isEnabled = false
        logBuffer.clear()
        logView.text = ""
        val sink: (String) -> Unit = { line -> appendLog(line) }
        thread(name = "airpods-probe-v16") {
            try {
                V16Probe(device, sink).run()
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
        clipboard.setPrimaryClip(ClipData.newPlainText("AirPods probe v16 log", logBuffer.toString()))
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

private class V16Probe(
    private val device: BluetoothDevice,
    private val emit: (String) -> Unit,
) {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun run() {
        log("=== AirPods control probe started ===")
        log("Device: ${safeName(device)} / ${device.address}")
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.")
        log("It does not install or replace the Xposed module.")
        log("--- v16 AACP 0x52 attribution + AACP-assisted commit probe ---")
        log("v15 showed no persisted 0x2A mutation through raw ATT routes 0x002A/0x0026/0x0028/0x0021.")
        log("v15's only new clue was an AACP packet with message/command 0x0052 after enabling the full indication path.")
        log("v16 therefore does three things:")
        log("  1) attributes exactly which CCCD enable causes AACP 0x0052, including the previously untested 0x0022 notify path.")
        log("  2) sends small AACP 0x0052 status/query variants and reads 0x002A after each.")
        log("  3) stages ATT writes and then sends AACP 0x0052 as a possible authenticated commit/notify trigger.")

        val baseline = session("v16 baseline/map") { aacp, att ->
            drainAacp(aacp, "v16 baseline/map post-init", timeoutMs = 1500)
            discoverHearingHandles(att)
            robustRead(att, 0x0021, "v16 baseline sibling 0x0021")
            robustRead(att, 0x0024, "v16 baseline sibling 0x0024")
            robustRead(att, 0x002A, "v16 baseline HEARING_AID_CONFIG")
        }

        if (baseline == null) {
            log("v16 aborted: could not read baseline handle 0x002A.")
            log("=== Probe finished ===")
            return
        }

        log("v16 baseline 0x2A value: ${hex(baseline)}")
        val target = controlledMutation(baseline)
        log("v16 controlled mutation target: ${hex(target)}")
        log("v16 mutation changes only byte[4] to 0x01 when possible; header bytes[0..3] stay unchanged.")

        runCccdAttribution(baseline)
        Thread.sleep(1200)
        runAacp52Variants(baseline, target)
        Thread.sleep(1200)
        val commitHit = runAacpCommitAfterAttWrites(baseline, target)

        val finalValue = session("v16 final check") { _, att ->
            robustRead(att, 0x002A, "v16 final HEARING_AID_CONFIG")
        }
        log("v16 final value equals baseline: ${finalValue != null && finalValue.contentEquals(baseline)}")
        if (commitHit) {
            log("v16 result: an AACP-assisted route produced the controlled 0x2A readback. Use the HIT section as the next implementation target.")
        } else {
            log("v16 result: no AACP 0x52-assisted route produced a persisted 0x2A mutation. Next target should be decoding the full Apple hearing-test/profile transaction, not raw ATT writes.")
        }
        log("v16 AACP 0x52 attribution + commit probe complete.")
        log("=== Probe finished ===")
    }

    private fun runCccdAttribution(baseline: ByteArray) {
        log("--- v16 CCCD attribution session: find what triggers AACP 0x0052 ---")
        session("v16 CCCD attribution") { aacp, att ->
            drainAacp(aacp, "v16 CCCD attribution post-init", timeoutMs = 1500)
            drainAtt(att, "v16 CCCD attribution post-init")
            val before = robustRead(att, 0x002A, "v16 CCCD attribution baseline check")
            log("v16 CCCD attribution: before equals baseline: ${before != null && before.contentEquals(baseline)}")

            for (step in cccdSteps()) {
                log("v16 CCCD attribution: enabling ${step.name} at ${h(step.handle)} = ${hex(step.value)}")
                robustWriteRequest(att, step.handle, step.value, "v16 CCCD attribution ${step.name}")
                drainAtt(att, "v16 CCCD attribution after ${step.name}", timeoutMs = 600)
                val aacpPackets = drainAacp(aacp, "v16 CCCD attribution after ${step.name}", timeoutMs = 2200)
                val saw52 = aacpPackets.any { it.size >= 6 && u16(it, 4) == 0x0052 }
                log("v16 CCCD attribution: ${step.name} produced AACP 0x0052: $saw52")
                Thread.sleep(350)
            }

            val after = robustRead(att, 0x002A, "v16 CCCD attribution final 0x2A")
            log("v16 CCCD attribution: final 0x2A equals baseline: ${after != null && after.contentEquals(baseline)}")
        }
    }

    private fun runAacp52Variants(baseline: ByteArray, target: ByteArray) {
        log("--- v16 AACP 0x0052 direct status/query variants ---")
        session("v16 AACP 0x52 variants") { aacp, att ->
            drainAacp(aacp, "v16 AACP 0x52 variants post-init", timeoutMs = 1500)
            enableAllNotifyIndicate(att, aacp, "v16 AACP 0x52 variants")
            val before = robustRead(att, 0x002A, "v16 AACP 0x52 variants before")
            log("v16 AACP 0x52 variants: before equals baseline: ${before != null && before.contentEquals(baseline)}")

            for (variant in aacpVariants()) {
                sendAacp(aacp, variant.name, variant.packet)
                drainAtt(att, "v16 AACP 0x52 variants after ${variant.name}", timeoutMs = 700)
                val after = robustRead(att, 0x002A, "v16 AACP 0x52 variants readback after ${variant.name}")
                val equalsTarget = after != null && after.contentEquals(target)
                val equalsBaseline = after != null && after.contentEquals(baseline)
                log("v16 AACP 0x52 variants: ${variant.name} readback equals mutated: $equalsTarget, equals baseline: $equalsBaseline")
                if (after != null && !equalsBaseline) log("v16 AACP 0x52 variants: ${variant.name} readback value: ${hex(after)}")
                Thread.sleep(600)
            }

            // Try to leave any 0x52 status bit in the lower/false-looking state observed by the variant set.
            sendAacp(aacp, "v16 AACP 0x52 variants final observed-status-00 restore", bytes("04 00 04 00 52 00 03 00 02 01 00"))
            robustWriteCommand(att, 0x002A, baseline, "v16 AACP 0x52 variants direct 0x2A baseline fallback")
        }
    }

    private fun runAacpCommitAfterAttWrites(baseline: ByteArray, target: ByteArray): Boolean {
        log("--- v16 AACP 0x0052 commit-after-ATT-staged-write session ---")
        var anyHit = false
        session("v16 AACP commit after ATT") { aacp, att ->
            drainAacp(aacp, "v16 AACP commit after ATT post-init", timeoutMs = 1500)
            enableAllNotifyIndicate(att, aacp, "v16 AACP commit after ATT")
            val before = robustRead(att, 0x002A, "v16 AACP commit before routes")
            log("v16 AACP commit after ATT: before equals baseline: ${before != null && before.contentEquals(baseline)}")

            for (route in commitRoutes()) {
                log("v16 AACP commit route: ${route.name} staging target on ${h(route.handle)} using ${route.writeKind}")
                when (route.writeKind) {
                    WriteKind.REQUEST -> robustWriteRequest(att, route.handle, target, "v16 AACP commit stage ${route.name}")
                    WriteKind.COMMAND -> robustWriteCommand(att, route.handle, target, "v16 AACP commit stage ${route.name}")
                }
                drainAtt(att, "v16 AACP commit ${route.name} after staging", timeoutMs = 700)
                drainAacp(aacp, "v16 AACP commit ${route.name} after staging", timeoutMs = 900)

                for (commit in commitVariants()) {
                    sendAacp(aacp, "v16 AACP commit ${route.name} ${commit.name}", commit.packet)
                    drainAtt(att, "v16 AACP commit ${route.name} after ${commit.name}", timeoutMs = 800)
                    val readback = robustRead(att, 0x002A, "v16 AACP commit ${route.name} readback after ${commit.name}")
                    val equalsTarget = readback != null && readback.contentEquals(target)
                    val equalsBaseline = readback != null && readback.contentEquals(baseline)
                    log("v16 AACP commit ${route.name} ${commit.name}: readback equals mutated: $equalsTarget, equals baseline: $equalsBaseline")
                    if (readback != null && !equalsBaseline) log("v16 AACP commit ${route.name} ${commit.name}: readback value: ${hex(readback)}")
                    if (equalsTarget) anyHit = true
                    Thread.sleep(500)
                }

                log("v16 AACP commit route: ${route.name} restore attempt.")
                when (route.writeKind) {
                    WriteKind.REQUEST -> robustWriteRequest(att, route.handle, baseline, "v16 AACP commit restore ${route.name}")
                    WriteKind.COMMAND -> robustWriteCommand(att, route.handle, baseline, "v16 AACP commit restore ${route.name}")
                }
                sendAacp(aacp, "v16 AACP commit ${route.name} restore observed-status-00", bytes("04 00 04 00 52 00 03 00 02 01 00"))
                robustWriteCommand(att, 0x002A, baseline, "v16 AACP commit ${route.name} direct 0x2A baseline fallback")
                Thread.sleep(900)
            }
        }
        log("v16 AACP commit-after-ATT overall HIT: $anyHit")
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
        socket.outputStream.write(packet)
        socket.outputStream.flush()
        val packets = mutableListOf<ByteArray>()
        val first = readPacket(socket.inputStream, 900)
        if (first != null) {
            packets.add(first)
            log("AACP response to $label packet 1: ${hex(first)}")
            describeAacp("AACP response to $label packet 1", first)
        } else {
            log("AACP response to $label: no immediate packet.")
        }
        packets.addAll(drainAacp(socket, "AACP response tail for $label", timeoutMs = 1300))
        return packets
    }

    private fun discoverHearingHandles(att: BluetoothSocket) {
        log("--- v16 ATT discovery around hearing handles 0x0020–0x0038 ---")
        findInfoPaged(att, 0x0020, 0x0038)
        readByTypeCharacteristicDeclarations(att, 0x0020, 0x0038)
        log("v16 expected map from v14/v15:")
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
    0x0052 -> " (AACP 0x52 status/commit clue from v15)"
    else -> ""
}
