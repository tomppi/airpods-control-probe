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
import java.io.OutputStream
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
            text = "AirPods control probe v15\nGitHub-runner Android repo build"
            textSize = 18f
        }
        val note = TextView(this).apply {
            text = "v15 builds on v14. It keeps the robust read/write filtering, adds the missing 0x0037/0x0038 indicate channel, and tests sibling write routes 0x0021/0x0026/0x0028 as possible save paths for the 0x002A hearing-aid state."
            textSize = 13f
        }
        spinner = Spinner(this)
        refreshButton = Button(this).apply { text = "Refresh paired devices" }
        runButton = Button(this).apply { text = "Run v15 probe"; isEnabled = false }
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
        appendLog("Loaded ${devices.size} paired device(s). Pick AirPods Pro, then run v15.")
    }

    private fun startProbe() {
        val device = selectedDevice ?: return
        runButton.isEnabled = false
        logBuffer.clear()
        logView.text = ""
        val sink: (String) -> Unit = { line -> appendLog(line) }
        thread(name = "airpods-probe-v15") {
            try {
                V15Probe(device, sink).run()
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
        clipboard.setPrimaryClip(ClipData.newPlainText("AirPods probe v15 log", logBuffer.toString()))
        appendLog("Log copied to clipboard.")
    }
}

private enum class WriteKind { REQUEST, COMMAND }

private data class Route(
    val name: String,
    val handle: Int,
    val writeKind: WriteKind,
    val enable2aNotify: Boolean = false,
    val enableCommandIndications: Boolean = false,
)

private class V15Probe(
    private val device: BluetoothDevice,
    private val emit: (String) -> Unit,
) {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun run() {
        log("=== AirPods control probe started ===")
        log("Device: ${safeName(device)} / ${device.address}")
        log("This app relies on the existing LibrePods Xposed module being active in com.android.bluetooth.")
        log("It does not install or replace the Xposed module.")
        log("--- v15 hearing-aid sibling route + full indication probe ---")
        log("v15 keeps v14 robust ATT filtering and adds tests that v14 did not run:")
        log("  1) enable 0x0037/0x0038 indication path as well as 0x002E/0x0031/0x0034.")
        log("  2) try sibling write routes 0x0021, 0x0026, and 0x0028, then read 0x002A as the state/output characteristic.")
        log("  3) keep each route isolated in a fresh AACP+ATT session.")

        val baseline = session("v15 baseline/map") { aacp, att ->
            drainAacp(aacp, "v15 baseline/map post-init")
            discoverHearingHandles(att)
            safeReadRange(att, 0x0020, 0x0038)
            robustRead(att, 0x002A, "v15 baseline HEARING_AID_CONFIG")
        }

        if (baseline == null) {
            log("v15 aborted: could not read baseline handle 0x002A.")
            log("=== Probe finished ===")
            return
        }

        log("v15 baseline 0x2A value: ${hex(baseline)}")
        val target = controlledMutation(baseline)
        log("v15 controlled mutation target: ${hex(target)}")
        log("v15 mutation changes only byte[4] to 0x01 when possible; header bytes[0..3] stay unchanged.")

        val routes = listOf(
            Route("control: direct 0x2A Write Command 0x52, no CCCD", 0x002A, WriteKind.COMMAND),
            Route("new route: sibling 0x0026 Write Command 0x52, no CCCD", 0x0026, WriteKind.COMMAND),
            Route("new route: sibling 0x0028 Write Request 0x12, no CCCD", 0x0028, WriteKind.REQUEST),
            Route("new route: sibling 0x0021 Write Command 0x52, no CCCD", 0x0021, WriteKind.COMMAND),
            Route("0x0026 + 0x2A notify + command indications 0x2F/0x32/0x35/0x38", 0x0026, WriteKind.COMMAND, enable2aNotify = true, enableCommandIndications = true),
            Route("0x0028 + 0x2A notify + command indications 0x2F/0x32/0x35/0x38", 0x0028, WriteKind.REQUEST, enable2aNotify = true, enableCommandIndications = true),
            Route("0x0021 + 0x2A notify + command indications 0x2F/0x32/0x35/0x38", 0x0021, WriteKind.COMMAND, enable2aNotify = true, enableCommandIndications = true),
            Route("control: direct 0x2A + all notifications/indications", 0x002A, WriteKind.COMMAND, enable2aNotify = true, enableCommandIndications = true),
            Route("legacy control: direct 0x2A Write Request 0x12 + all indications", 0x002A, WriteKind.REQUEST, enable2aNotify = true, enableCommandIndications = true),
        )

        var hit = false
        for (route in routes) {
            val routeHit = runRoute(route, baseline, target)
            hit = hit || routeHit
            Thread.sleep(1200)
        }

        val finalValue = session("v15 final check") { _, att ->
            robustRead(att, 0x002A, "v15 final HEARING_AID_CONFIG")
        }
        log("v15 final value equals baseline: ${finalValue != null && finalValue.contentEquals(baseline)}")
        if (hit) {
            log("v15 result: at least one route produced a mutated 0x2A readback. Use the HIT route as the app-side save path candidate.")
        } else {
            log("v15 result: no route produced a persisted 0x2A mutation. This strongly points away from raw ATT value writes and toward an AACP/authenticated commit or higher-level Apple hearing-test transaction.")
        }
        log("v15 sibling route + full indication probe complete.")
        log("=== Probe finished ===")
    }

    private fun runRoute(route: Route, baseline: ByteArray, target: ByteArray): Boolean {
        log("--- v15 isolated route: ${route.name} ---")
        var routeHit = false
        session(route.name) { aacp, att ->
            drainAacp(aacp, "${route.name} post-init")
            if (route.enable2aNotify) {
                log("${route.name}: enabling 0x002A notifications through CCCD 0x002B = 01 00")
                robustWriteRequest(att, 0x002B, byteArrayOf(0x01, 0x00), "${route.name} enable 0x002B")
            }
            if (route.enableCommandIndications) {
                for (cccd in intArrayOf(0x002F, 0x0032, 0x0035, 0x0038)) {
                    log("${route.name}: enabling command indication descriptor ${h(cccd)} = 02 00")
                    robustWriteRequest(att, cccd, byteArrayOf(0x02, 0x00), "${route.name} enable ${h(cccd)}")
                }
            }
            drainAacp(aacp, "${route.name} after CCCD setup")
            drainAtt(att, "${route.name} after CCCD setup")

            val before = robustRead(att, 0x002A, "${route.name} before route write")
            log("${route.name}: before-write equals baseline: ${before != null && before.contentEquals(baseline)}")
            log("${route.name}: writing target to route handle ${h(route.handle)} using ${route.writeKind}")

            when (route.writeKind) {
                WriteKind.REQUEST -> robustWriteRequest(att, route.handle, target, "${route.name} route write ${h(route.handle)}")
                WriteKind.COMMAND -> robustWriteCommand(att, route.handle, target, "${route.name} route write ${h(route.handle)}")
            }

            drainAtt(att, "${route.name} immediately after route write")
            drainAacp(aacp, "${route.name} immediately after route write")

            for (delay in intArrayOf(0, 250, 1000, 3000)) {
                if (delay > 0) Thread.sleep(delay.toLong())
                drainAtt(att, "${route.name} readback after ${delay}ms pre-read drain", maxPackets = 4, timeoutMs = 120)
                val readback = robustRead(att, 0x002A, "${route.name} readback after ${delay}ms")
                val equalsTarget = readback != null && readback.contentEquals(target)
                val equalsBaseline = readback != null && readback.contentEquals(baseline)
                log("${route.name}: readback after ${delay}ms equals mutated: $equalsTarget, equals baseline: $equalsBaseline")
                if (equalsTarget) routeHit = true
            }

            log("${route.name}: restore attempt using route handle ${h(route.handle)} plus direct 0x2A 0x52 fallback.")
            when (route.writeKind) {
                WriteKind.REQUEST -> robustWriteRequest(att, route.handle, baseline, "${route.name} route restore ${h(route.handle)}")
                WriteKind.COMMAND -> robustWriteCommand(att, route.handle, baseline, "${route.name} route restore ${h(route.handle)}")
            }
            robustWriteCommand(att, 0x002A, baseline, "${route.name} direct 0x2A fallback restore")
            routeHit
        }
        log("${route.name}: HIT route candidate: $routeHit")
        return routeHit
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

    private fun discoverHearingHandles(att: BluetoothSocket) {
        log("--- v15 ATT discovery around hearing handles 0x0020–0x0038 ---")
        findInfoPaged(att, 0x0020, 0x0038)
        readByTypeCharacteristicDeclarations(att, 0x0020, 0x0038)
        log("v15 expected map from v14:")
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

    private fun safeReadRange(att: BluetoothSocket, start: Int, end: Int) {
        log("--- v15 individual safe reads ${h(start)}–${h(end)} ---")
        for (handle in start..end) {
            val value = robustRead(att, handle, "nearby handle ${h(handle)}", timeoutMs = 1200)
            if (value == null) log("nearby handle ${h(handle)}: read failed/not readable")
            Thread.sleep(80)
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
                0x1B, 0x1D -> log("ATT robust read observed notification/indication while waiting for read: ${hex(packet)}")
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
                0x0B, 0x1B, 0x1D -> log("ATT robust write ignored unrelated packet while waiting for 0x13/error.")
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

    private fun drainAtt(socket: BluetoothSocket, label: String, maxPackets: Int = 8, timeoutMs: Long = 900) {
        var count = 0
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (count < maxPackets && SystemClock.elapsedRealtime() < deadline) {
            val p = readPacket(socket.inputStream, 120) ?: continue
            count++
            log("$label: drained ATT packet $count opcode 0x${p[0].u().hex2()}: ${hex(p)}")
        }
        if (count == 0) log("$label: no unsolicited/stale ATT packets.")
        else log("$label: drained $count ATT packet(s).")
    }

    private fun drainAacp(socket: BluetoothSocket, label: String, maxPackets: Int = 12, timeoutMs: Long = 900) {
        var count = 0
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (count < maxPackets && SystemClock.elapsedRealtime() < deadline) {
            val p = readPacket(socket.inputStream, 120) ?: continue
            count++
            log("AACP drain $label packet $count: ${hex(p)}")
            describeAacp("AACP drain $label packet $count", p)
        }
        if (count == 0) log("AACP drain $label: no packets.")
    }

    private fun describeAacp(label: String, p: ByteArray) {
        if (p.size >= 6) {
            log("  $label header: type/service? 0x${u16(p, 0).hex4()} / 0x${u16(p, 2).hex4()}")
            log("  $label message/command? 0x${u16(p, 4).hex4()}${aacpName(u16(p, 4))}")
        }
        val interesting = mapOf(
            0x22 to "hearingAidCapability?",
            0x31 to "hearingAidV2Capability?",
            0xC0 to "hearingAidCapability 0xC0?",
            0xD0 to "hearingTestCapability?",
            0x28 to "hearingProtectionPPECapability?",
            0x26 to "heartRateMonitorCapability?",
        )
        val hits = mutableListOf<String>()
        for (i in p.indices) {
            val name = interesting[p[i].u()] ?: continue
            hits.add("$name at offset $i")
        }
        if (hits.isNotEmpty()) {
            log("  $label heuristic capability scan:")
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
    else -> ""
}
