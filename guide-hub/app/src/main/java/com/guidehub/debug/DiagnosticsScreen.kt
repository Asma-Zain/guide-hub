package com.guidehub.debug

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guidehub.core.AppContainer
import com.guidehub.core.LinkState
import com.guidehub.core.PositionUpdate
import com.guidehub.nav.Route
import com.guidehub.core.Severity
import com.guidehub.nav.Geo
import com.guidehub.protocol.CompassMode
import com.guidehub.protocol.CompassOpcode
import com.guidehub.recording.EventJson
import com.guidehub.core.BusEvent
import com.guidehub.core.CompassAckEvent
import com.guidehub.core.FrameQuality
import com.guidehub.core.HazardsUpdate
import com.guidehub.core.LinkId
import com.guidehub.core.MarkerDetection
import com.guidehub.core.ObjectDetections
import com.guidehub.core.TofSweep
import com.guidehub.core.VoDelta
import com.guidehub.ui.ExpandableCard
import com.guidehub.ui.InfoRow
import com.guidehub.ui.Palette
import com.guidehub.ui.SectionCard
import com.guidehub.ui.SimulatedBadge
import com.guidehub.ui.SliderSetting
import com.guidehub.ui.Space
import com.guidehub.ui.StatusPill
import com.guidehub.ui.SwitchSetting
import com.guidehub.ui.Tone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.objdetect.QRCodeEncoder
import java.net.NetworkInterface
import kotlin.math.cos
import kotlin.math.sin

/**
 * FR-10 diagnostics (behind auth). Device status is always visible; the technical tools are grouped
 * in collapsible sections so they stay out of the way during a demo. Diagnostics never speak.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(c: AppContainer) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(300); tick++ } }
    val links by c.linkStates.collectAsStateWithLifecycle()
    val linkSet by c.links.collectAsStateWithLifecycle()
    val sweep by c.orchestrator.lastSweepFlow.collectAsStateWithLifecycle()
    val wall by c.orchestrator.lastWall.collectAsStateWithLifecycle()
    val pos by c.orchestrator.positionFlow.collectAsStateWithLifecycle()
    val hazards by c.orchestrator.hazards.collectAsStateWithLifecycle()
    val route by c.orchestrator.route.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val voice by c.audio.voiceStatus.collectAsStateWithLifecycle()
    val demoRunning by c.demoRunning.collectAsStateWithLifecycle()
    val sim = linkSet?.simulated ?: settings.simulatorMode

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        SectionCard(title = "Device connection status", subtitle = if (sim) "Simulator mode: these devices are simulated, not physical hardware." else "Real Bluetooth and Wi-Fi links.") {
            listOf(
                LinkId.COMPASS to "Tactile compass",
                LinkId.HEADBAND_BLE to "Headband sensors (Bluetooth)",
                LinkId.HEADBAND_WIFI to "Headband camera (Wi-Fi)",
                LinkId.EARBUDS to "Earbuds",
            ).forEach { (id, name) ->
                val st = links[id]
                val (text, tone) = when (st?.state) {
                    LinkState.CONNECTED -> "Connected" to Tone.Success
                    LinkState.DEGRADED -> "Not responding" to Tone.Warning
                    LinkState.CONNECTING -> "Connecting" to Tone.Neutral
                    LinkState.DISCONNECTED -> "Disconnected" to Tone.Danger
                    null -> (if (id == LinkId.HEADBAND_WIFI && sim) "Not used in simulator" else "No connection yet") to Tone.Neutral
                }
                val simulatedLink = sim && (id == LinkId.COMPASS || id == LinkId.HEADBAND_BLE)
                InfoRow(name, "", extra = {
                    StatusPill(text, tone)
                    if (simulatedLink) SimulatedBadge()
                })
            }
            HorizontalDivider(color = Palette.Outline)
            InfoRow("Speech recognition", c.sttDescription())
            InfoRow("Spoken voice", voice.note)
            if (demoRunning) StatusPill("Demo scenario running", Tone.Simulated)
        }

        ExpandableCard("Position and route", "Where the app thinks the user is, compared with the map.", initiallyExpanded = true, trailing = { if (sim) SimulatedBadge() }) {
            pos?.let { p ->
                InfoRow("Corridor", "${p.edgeFrom ?: "?"} \u2192 ${p.edgeTo ?: "?"}")
                InfoRow("Along corridor", "%.1f m".format(p.s))
                InfoRow("Side offset", "%.2f m".format(p.lateralOffsetM))
                InfoRow("Heading", "%.0f\u00b0 (map frame)".format(p.yawDeg))
                InfoRow("Position confidence", "%.0f%%".format(p.confidence * 100))
                InfoRow("Heading confidence", "%.0f%%".format(p.headingConfidence * 100))
                InfoRow("Since last marker", "%.1f m".format(p.distanceSinceFixM))
                p.lastCorrectionM?.let { InfoRow("Last marker correction", "%.2f m".format(it)) }
                InfoRow("Updated", timeOf(p.ts))
            } ?: Text("No position fix yet. A marker must be seen first.", color = Palette.InkSoft)
            route?.let { r -> InfoRow("Route", r.nodes.joinToString(" \u2192 ") + "  (%.0f m)".format(r.totalM)) }
            MapView(c, pos, route, sim, tick)
        }

        ExpandableCard("ToF sweep", "Time-of-flight distance sensor on the headband, swept \u00b160\u00b0 at head height.", trailing = { if (sim) SimulatedBadge() }) {
            wall?.let { w ->
                InfoRow("Walls detected", "${w.wallsSeen}")
                InfoRow("Angle to corridor", "%.1f\u00b0".format(w.yawOffsetDeg))
                InfoRow("Offset from centre", w.lateralOffsetM?.let { "%.2f m".format(it) } ?: "\u2013")
                InfoRow("Fit quality (inliers)", "%.0f%%".format(w.inlierRatio * 100))
            }
            sweep?.let { InfoRow("Last sweep", timeOf(it.ts)) }
            Canvas(Modifier.fillMaxWidth().height(220.dp).background(Palette.Panel)) {
                val origin = Offset(size.width / 2, size.height - 8)
                val scale = (size.height - 16) / 4f
                for (r in 1..4) drawCircle(Palette.Outline, r * scale, origin, style = Stroke(1.5f))
                sweep?.points?.forEach { p ->
                    if (p.rangeM <= 0.05) return@forEach
                    val a = Math.toRadians(p.angleDeg)
                    val pt = Offset(origin.x + (p.rangeM * sin(a) * scale).toFloat(), origin.y - (p.rangeM * cos(a) * scale).toFloat())
                    drawLine(Color(0x332563A6), origin, pt)
                    drawCircle(Palette.Info, 4f, pt)
                }
                hazards.forEach { h ->
                    val a = Math.toRadians(h.bearingDeg)
                    val pt = Offset(origin.x + (h.rangeM * sin(a) * scale).toFloat(), origin.y - (h.rangeM * cos(a) * scale).toFloat())
                    drawCircle(if (h.severity == Severity.CRITICAL) Palette.Danger else Palette.Warn, 10f, pt, style = Stroke(3f))
                }
            }
            Text("Rings every 1 m. Blue = distance returns, red/amber circles = obstacles in the walking path.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
            hazards.forEach {
                Text(
                    "${it.severity.name.lowercase()} \u00b7 ${it.label} \u00b7 %.2f m \u00b7 %.0f\u00b0 \u00b7 ${it.heightZone.name.lowercase()} height${if (it.rangeFromTof) "" else " (camera estimate)"}".format(it.rangeM, it.bearingDeg),
                    color = Palette.Warn, style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ExpandableCard("Headband camera and marker detection", "Frames from the headband, with detected markers and objects outlined.") {
            val frame = c.frameProcessor?.debugFrame?.collectAsStateWithLifecycle()?.value
            if (frame == null) Text(
                if (sim) "Simulator: marker sightings and objects are generated directly; there are no camera frames." else "No frames yet. The headband must connect to ws://<phone-ip>:${settings.wsPort}/frames.",
                color = Palette.InkSoft, style = MaterialTheme.typography.bodyMedium,
            )
            else FrameView(frame.bitmap, frame.markers.map { it.corners to "#${it.detection.id} ${"%.1f".format(it.detection.rangeM)} m" }, frame.objects.map { listOf(it.left, it.top, it.right, it.bottom) to it.label })
            frame?.let {
                InfoRow("Brightness", "%.0f / 255".format(it.brightness))
                InfoRow("Sharpness", "%.0f".format(it.sharpness))
                InfoRow("Processing time", "${c.frameProcessor?.lastProcessingMs} ms")
            }
            if (!sim) PairingPanel(c, settings.wsPort)
        }

        ExpandableCard("Compass bearing and ACK log", "Every command sent to the tactile compass and its acknowledgement.", trailing = { if (sim) SimulatedBadge() }) {
            CompassPanel(c, tick)
        }

        ExpandableCard("Simulator controls", "Inject events into the simulated world. Available in simulator mode only.") {
            if (!sim) Text("Turn on Simulator mode in Settings to use these.", color = Palette.InkSoft)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedButton(onClick = { c.simWorld.addObstacle("person", c.simWorld.x + 2.0 * Geo.dx(c.simWorld.yaw), c.simWorld.y + 2.0 * Geo.dy(c.simWorld.yaw), durationMs = 5_000) }) { Text("Person 2 m ahead (5 s)") }
                OutlinedButton(onClick = { c.mockCompass.simulateDrop(3_000) }) { Text("Drop compass (3 s)") }
                OutlinedButton(onClick = { c.mockHeadband.simulateDrop(4_000) }) { Text("Drop headband (4 s)") }
                OutlinedButton(onClick = { c.stopDemo() }) { Text("Reset simulator") }
            }
        }

        ExpandableCard("Event history and session recording", "Recent events on the internal event bus, and JSONL session recordings.") {
            var showSensors by remember { mutableStateOf(false) }
            SwitchSetting("Include sensor stream", "ToF sweeps, odometry, positions and detections arrive many times per second.", showSensors) { showSensors = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedButton(onClick = { c.settings.update { it.copy(recordSession = !it.recordSession) } }) { Text(if (settings.recordSession) "Stop recording" else "Start recording") }
                OutlinedButton(onClick = {
                    c.recorder.list().firstOrNull { it != c.recorder.currentFile || !c.recorder.recording }?.let { f -> c.replayer.replay(f.readLines()) }
                }) { Text("Replay latest recording") }
            }
            c.recorder.currentFile?.let { Text("Recording to ${it.name}", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft) }
            val tail = remember(tick, showSensors) {
                c.bus.tail.snapshot().filter { showSensors || !isSensorStream(it) }.takeLast(60).reversed()
            }
            tail.forEach { e ->
                Text(
                    "${timeOf(e.ts)}  ${(EventJson.encode(e) ?: e.toString()).take(150)}",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.Ink,
                )
            }
        }
    }
}

private fun isSensorStream(e: BusEvent) =
    e is TofSweep || e is VoDelta || e is PositionUpdate || e is ObjectDetections || e is HazardsUpdate || e is CompassAckEvent || e is FrameQuality || e is MarkerDetection

private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
private fun timeOf(ts: Long): String = timeFormat.get()!!.format(Date(ts))

@Composable
private fun MapView(c: AppContainer, pos: PositionUpdate?, route: Route?, sim: Boolean, tick: Long) {
    val g = c.orchestrator.graph ?: return
    val xs = g.map.nodes.map { it.x }
    val ys = g.map.nodes.map { it.y }
    val minX = xs.min() - 2; val maxX = xs.max() + 2; val minY = ys.min() - 2; val maxY = ys.max() + 2
    Canvas(Modifier.fillMaxWidth().aspectRatio(((maxX - minX) / (maxY - minY)).toFloat().coerceIn(1f, 4f)).background(Palette.Panel)) {
        val sx = size.width / (maxX - minX).toFloat()
        val sy = size.height / (maxY - minY).toFloat()
        val s = minOf(sx, sy)
        fun p(x: Double, y: Double) = Offset(((x - minX) * s).toFloat(), (size.height - (y - minY) * s).toFloat())
        g.map.edges.forEach { e ->
            val a = g.nodes.getValue(e.from); val b = g.nodes.getValue(e.to)
            drawLine(Palette.Outline, p(a.x, a.y), p(b.x, b.y), strokeWidth = (e.width * s).toFloat())
        }
        route?.edges?.forEach { e ->
            val a = g.nodes.getValue(e.from); val b = g.nodes.getValue(e.to)
            drawLine(Palette.Accent, p(a.x, a.y), p(b.x, b.y), strokeWidth = 4f)
        }
        g.map.nodes.forEach { n -> drawCircle(if (n.marker != null) Palette.Info else Palette.InkSoft, 6f, p(n.x, n.y)) }
        if (sim && tick >= 0) { // tick forces a redraw of the (non-observable) simulator truth
            val w = c.simWorld
            drawCircle(Palette.Indigo.copy(alpha = 0.6f), 9f, p(w.x, w.y))
            c.simWorld.obstaclesSnapshot().forEach { o -> drawCircle(Palette.Danger, (o.radius * s).toFloat().coerceAtLeast(5f), p(o.x, o.y)) }
        }
        pos?.let { u ->
            val o = p(u.x, u.y)
            drawCircle(Palette.Accent, 8f, o)
            val tip = Offset(o.x + (Geo.dx(u.yawDeg) * 24).toFloat(), o.y - (Geo.dy(u.yawDeg) * 24).toFloat())
            drawLine(Palette.Accent, o, tip, strokeWidth = 4f)
        }
    }
    Text(
        "Teal arrow = estimated position and heading. Blue dots = marker locations." + if (sim) " Purple = simulated true position, red = simulated obstacles." else "",
        style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft,
    )
}

@Composable
private fun FrameView(bmp: Bitmap, markers: List<Pair<List<Pair<Double, Double>>, String>>, boxes: List<Pair<List<Float>, String>>) {
    Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height)) {
        Image(bmp.asImageBitmap(), contentDescription = "Latest headband frame", modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / bmp.width
            markers.forEach { (corners, _) ->
                val path = Path()
                corners.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo((x * k).toFloat(), (y * k).toFloat()) else path.lineTo((x * k).toFloat(), (y * k).toFloat()) }
                path.close()
                drawPath(path, Palette.Ok, style = Stroke(4f))
            }
            boxes.forEach { (b, _) ->
                drawRect(Palette.Warn, Offset(b[0] * k, b[1] * k), androidx.compose.ui.geometry.Size((b[2] - b[0]) * k, (b[3] - b[1]) * k), style = Stroke(3f))
            }
        }
    }
    markers.forEach { Text("Marker ${it.second}", color = Palette.Ok, style = MaterialTheme.typography.bodySmall) }
    boxes.forEach { Text("Object ${it.second}", color = Palette.Warn, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun PairingPanel(c: AppContainer, port: Int) {
    val token = remember { c.settings.pairingToken }
    val ip = remember {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }?.hostAddress
        }.getOrNull() ?: "<phone-ip>"
    }
    val payload = "ws://$ip:$port|$token"
    Text("Headband pairing (scan this on the headband, or enter it manually):", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
    Text(payload, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val qr = remember(payload) {
        runCatching {
            val m = Mat()
            QRCodeEncoder.create().encode(payload, m)
            val big = Mat()
            org.opencv.imgproc.Imgproc.resize(m, big, org.opencv.core.Size(m.cols() * 8.0, m.rows() * 8.0), 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_NEAREST)
            val bmp = Bitmap.createBitmap(big.cols(), big.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(big, bmp)
            m.release(); big.release()
            bmp
        }.getOrNull()
    }
    qr?.let { Image(it.asImageBitmap(), contentDescription = "Pairing QR code", modifier = Modifier.size(200.dp)) }
    OutlinedButton(onClick = { c.settings.regeneratePairingToken(); c.restartVision() }) { Text("New pairing token") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompassPanel(c: AppContainer, tick: Long) {
    val link = c.links.collectAsStateWithLifecycle().value?.compass ?: return
    val scope = rememberCoroutineScope()
    var bearing by remember { mutableFloatStateOf(0f) }
    val needle by link.needleDeg.collectAsStateWithLifecycle()
    val degraded by link.degraded.collectAsStateWithLifecycle()
    InfoRow("Needle position (from ACK)", needle?.let { "$it\u00b0" } ?: "\u2013", extra = { if (degraded) StatusPill("Not responding", Tone.Warning) })
    Text("0\u00b0 = straight ahead, +90\u00b0 = right, \u00b1180\u00b0 = behind.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
    SliderSetting("Test bearing", "Send a manual bearing with SET_BEARING to check the needle.", bearing, -180f..180f, { "${it.toInt()}\u00b0" }) { bearing = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { scope.launch { link.send(CompassOpcode.SET_BEARING, bearing.toInt(), CompassMode.NAVIGATE) } }) { Text("SET_BEARING") }
        OutlinedButton(onClick = { scope.launch { link.send(CompassOpcode.SET_MODE, 0, CompassMode.ARRIVED) } }) { Text("ARRIVED") }
        OutlinedButton(onClick = { scope.launch { link.send(CompassOpcode.SET_MODE, bearing.toInt(), CompassMode.HAZARD) } }) { Text("HAZARD") }
        OutlinedButton(onClick = { scope.launch { link.send(CompassOpcode.SET_MODE, 0, CompassMode.IDLE) } }) { Text("IDLE") }
        OutlinedButton(onClick = { scope.launch { link.send(CompassOpcode.CALIBRATE, 0, CompassMode.CALIBRATE) } }) { Text("CALIBRATE") }
        OutlinedButton(onClick = { scope.launch { link.send(CompassOpcode.PING, 0, CompassMode.IDLE) } }) { Text("PING") }
    }
    val rows = remember(tick) { link.log.snapshot().takeLast(25).reversed() }
    val acked = remember(tick) { link.log.snapshot().let { l -> if (l.isEmpty()) "-" else "${l.count { it.ack != null }}/${l.size} acked, mean ${l.mapNotNull { it.latencyMs }.average().let { if (it.isNaN()) "-" else "%.0f".format(it) }} ms" } }
    Text("Last ${link.log.size} commands: $acked", style = MaterialTheme.typography.bodySmall, color = Palette.Ink)
    Row { Text("time         seq op        deg  mode     try ack     ms", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.InkSoft) }
    rows.forEach { r ->
        Text(
            "%s %3d %-9s %4d %-8s %d   %-7s %s".format(timeOf(r.ts), r.command.seq, r.command.opcode.name.take(9), r.command.bearingDeg, r.command.mode.name, r.attempts, r.outcome, r.latencyMs?.toString() ?: "-"),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (r.ack == null) Palette.Danger else Palette.Ink,
        )
    }
}
