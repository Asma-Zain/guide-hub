package com.guidehub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.guidehub.nav.TurnType
import kotlin.math.roundToInt

// ===================== containers =====================

/** The standard content card: white, hairline outline, generous padding. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    tone: Tone = Tone.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = tone.container,
        border = BorderStroke(1.dp, tone.border),
    ) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (title != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = tone.content, modifier = Modifier.semantics { heading() })
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft) }
                }
            }
            content()
        }
    }
}

/** A card whose body collapses; used for advanced settings and diagnostics tools. */
@Composable
fun ExpandableCard(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Outline),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Space.touch)
                    .clickable(onClickLabel = if (open) "Collapse" else "Expand") { open = !open }
                    .semantics { role = Role.Button; stateDescription = if (open) "Expanded" else "Collapsed" }
                    .padding(horizontal = Space.l, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink, modifier = Modifier.semantics { heading() })
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                }
                trailing?.invoke()
                Chevron(open)
            }
            AnimatedVisibility(open) {
                Column(
                    Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, bottom = Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                    content = content,
                )
            }
        }
    }
}

// ===================== status =====================

enum class Tone(val container: Color, val content: Color, val border: Color) {
    Neutral(Palette.Surface, Palette.Ink, Palette.Outline),
    Accent(Palette.TealSoft, Palette.OnTealSoft, Color(0xFFA9DCD4)),
    Success(Palette.GreenSoft, Palette.Green, Color(0xFFB4E0C2)),
    Warning(Palette.AmberSoft, Palette.Amber, Color(0xFFF2D19C)),
    Danger(Palette.RedSoft, Palette.Red, Color(0xFFF4B8B2)),
    Simulated(Palette.IndigoSoft, Palette.Indigo, Color(0xFFCBC4F2)),
}

@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(tone.container, RoundedCornerShape(50))
            .border(1.dp, tone.border, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = tone.content) }
}

/** Marks anything driven by mock data, so a simulated device is never mistaken for real hardware. */
@Composable
fun SimulatedBadge(modifier: Modifier = Modifier) = StatusPill("SIMULATED", Tone.Simulated, modifier.semantics { contentDescription = "Simulated data" })

/** Label + value line used in summaries. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Palette.Ink, extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft, modifier = Modifier.weight(0.42f))
        Row(Modifier.weight(0.58f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, modifier = Modifier.weight(1f, fill = false))
            extra?.invoke()
        }
    }
}

// ===================== settings rows =====================

/** A switch with a readable title and an optional one-line explanation; the whole row is the target. */
@Composable
fun SwitchSetting(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch)
            .clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft) }
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Teal, uncheckedTrackColor = Palette.SurfaceAlt, uncheckedBorderColor = Palette.InkSoft, uncheckedThumbColor = Palette.InkSoft),
        )
    }
}

/**
 * A slider that always shows its current value with units, its limits, and what it does.
 * [format] renders a value for display (e.g. "1.0 m").
 */
@Composable
fun SliderSetting(
    title: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.titleSmall, color = Palette.TealDark)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps,
            modifier = Modifier.semantics { contentDescription = title; stateDescription = format(value) },
            colors = SliderDefaults.colors(thumbColor = Palette.Teal, activeTrackColor = Palette.Teal, inactiveTrackColor = Palette.Outline, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
        Row {
            Text(format(range.start), style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, modifier = Modifier.weight(1f))
            Text(format(range.endInclusive), style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        }
    }
}

/** Single choice shown as labelled chips. [options] pairs each value with its display label. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceSetting(title: String, description: String?, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
        description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            options.forEach { (o, label) ->
                FilterChip(
                    selected = o == value, onClick = { onChange(o) }, label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.heightIn(min = 44.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.TealSoft, selectedLabelColor = Palette.OnTealSoft,
                        labelColor = Palette.Ink, containerColor = Palette.Surface,
                    ),
                )
            }
        }
    }
}

@Composable
fun NumberSetting(title: String, description: String?, value: Int, unit: String, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val invalid = parsed == null || parsed !in range
    OutlinedTextField(
        text,
        { t -> text = t; t.toIntOrNull()?.takeIf { it in range }?.let(onChange) },
        label = { Text("$title ($unit)") },
        supportingText = { Text(if (invalid) "Enter a value from ${range.first} to ${range.last}" else description ?: "Allowed: ${range.first}–${range.last} $unit") },
        isError = invalid,
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
fun TextSetting(title: String, description: String?, value: String, placeholder: String = "", onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        text, { t -> text = t; onChange(t) },
        label = { Text(title) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
        supportingText = description?.let { { Text(it) } },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

// ===================== glyphs (drawn, so no icon dependency is needed) =====================

@Composable
private fun Chevron(open: Boolean) {
    Canvas(Modifier.size(20.dp).rotate(if (open) 180f else 0f)) {
        val p = Path().apply {
            moveTo(size.width * 0.2f, size.height * 0.38f)
            lineTo(size.width * 0.5f, size.height * 0.66f)
            lineTo(size.width * 0.8f, size.height * 0.38f)
        }
        drawPath(p, Palette.InkSoft, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun MicGlyph(color: Color, size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        drawRoundRect(color, topLeft = Offset(w * 0.34f, h * 0.06f), size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.52f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f))
        drawArc(color, 0f, 180f, false, topLeft = Offset(w * 0.2f, h * 0.24f), size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.5f), style = stroke)
        drawLine(color, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.92f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, h * 0.93f), Offset(w * 0.66f, h * 0.93f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    }
}

/** Large manoeuvre arrow for the direction card. */
@Composable
fun TurnArrow(turn: TurnType, color: Color, size: Dp = 96.dp) {
    Canvas(Modifier.size(size).semantics { contentDescription = turnDescription(turn) }) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.11f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun head(tip: Offset, dirDeg: Float) {
            val a = Math.toRadians(dirDeg.toDouble())
            val len = w * 0.2f
            fun wing(off: Double) = Offset(tip.x - (len * kotlin.math.cos(a + off)).toFloat(), tip.y - (len * kotlin.math.sin(a + off)).toFloat())
            drawPath(Path().apply { moveTo(wing(0.6).x, wing(0.6).y); lineTo(tip.x, tip.y); lineTo(wing(-0.6).x, wing(-0.6).y) }, color, style = stroke)
        }
        when (turn) {
            TurnType.ARRIVE -> {
                drawCircle(color, w * 0.3f, Offset(w / 2, h / 2), style = stroke)
                drawCircle(color, w * 0.1f, Offset(w / 2, h / 2))
            }
            TurnType.STRAIGHT, TurnType.START -> {
                drawLine(color, Offset(w / 2, h * 0.88f), Offset(w / 2, h * 0.14f), strokeWidth = sw, cap = StrokeCap.Round)
                head(Offset(w / 2, h * 0.12f), -90f)
            }
            TurnType.U_TURN -> {
                val p = Path().apply {
                    moveTo(w * 0.66f, h * 0.88f); lineTo(w * 0.66f, h * 0.38f)
                    cubicTo(w * 0.66f, h * 0.1f, w * 0.3f, h * 0.1f, w * 0.3f, h * 0.38f)
                    lineTo(w * 0.3f, h * 0.7f)
                }
                drawPath(p, color, style = stroke)
                head(Offset(w * 0.3f, h * 0.74f), 90f)
            }
            else -> {
                val right = turn == TurnType.RIGHT || turn == TurnType.SLIGHT_RIGHT
                val slight = turn == TurnType.SLIGHT_LEFT || turn == TurnType.SLIGHT_RIGHT
                val sx = if (right) w * 0.34f else w * 0.66f
                val ex = if (slight) (if (right) w * 0.78f else w * 0.22f) else (if (right) w * 0.86f else w * 0.14f)
                val ey = if (slight) h * 0.14f else h * 0.36f
                val cy = if (slight) h * 0.42f else ey
                val p = Path().apply {
                    moveTo(sx, h * 0.9f); lineTo(sx, h * 0.58f)
                    quadraticTo(sx, cy, ex, ey)
                }
                drawPath(p, color, style = stroke)
                // Arrowhead follows the curve's end tangent (control point -> end point).
                head(Offset(ex, ey), Math.toDegrees(kotlin.math.atan2((ey - cy).toDouble(), (ex - sx).toDouble())).toFloat())
            }
        }
    }
}

fun turnDescription(t: TurnType) = when (t) {
    TurnType.ARRIVE -> "Arrival"
    TurnType.STRAIGHT, TurnType.START -> "Straight ahead"
    TurnType.LEFT -> "Turn left"
    TurnType.RIGHT -> "Turn right"
    TurnType.SLIGHT_LEFT -> "Bear left"
    TurnType.SLIGHT_RIGHT -> "Bear right"
    TurnType.U_TURN -> "Turn around"
}

/** Short spoken-style distance for cards: "5 m", "12 m", "under 1 m". */
fun metresLabel(m: Double): String = if (m < 1.0) "under 1 m" else "${m.roundToInt()} m"

@Composable
fun SpacerS() = Spacer(Modifier.width(Space.s))
