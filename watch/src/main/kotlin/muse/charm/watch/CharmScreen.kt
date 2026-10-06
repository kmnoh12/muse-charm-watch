package muse.charm.watch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlin.random.Random

private val Ink = Color(0xFF1B1530)
private val BodyLight = Color(0xFFD9CEFF)
private val BodyDark = Color(0xFF9C88F5)
private val Cheek = Color(0xFFFF9EC0)
private val MouthDark = Color(0xFF3A1F4A)
private val Accent = Color(0xFFB9A6FF)

private const val TAU = (2 * PI).toFloat()

/**
 * The charm, drawn with vector shapes (an original character; swap in sprites later without
 * touching the state machine). [mouthLevel] 0..1 drives the mouth while speaking; null means
 * a synthesized talking motion, which is what the demo uses until real audio levels exist.
 */
@Composable
fun CharmScreen(
    state: CharmState,
    modifier: Modifier = Modifier,
    mouthLevel: Float? = null,
    showLabel: Boolean = true,
) {
    val clock = rememberInfiniteTransition(label = "clock")
    val breath by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "breath")
    val talk by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart), label = "talk")
    val ripple by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "ripple")
    val spin by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "spin")

    val listen by animateFloatAsState(if (state == CharmState.LISTENING) 1f else 0f, tween(300), label = "listen")
    val think by animateFloatAsState(if (state == CharmState.THINKING) 1f else 0f, tween(300), label = "think")
    val speak by animateFloatAsState(if (state == CharmState.SPEAKING) 1f else 0f, tween(200), label = "speak")

    val blink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2600L + Random.nextLong(1800))
            blink.animateTo(0.1f, tween(70))
            blink.animateTo(1f, tween(110))
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val mouth = if (speak > 0.01f) {
                val level = mouthLevel ?: (0.5f + 0.5f * sin(TAU * talk * 2f)) * (0.6f + 0.4f * sin(TAU * breath * 3.1f))
                level.coerceIn(0f, 1f) * speak
            } else {
                0f
            }
            drawCharm(
                breath = breath, talk = talk, ripple = ripple, spin = spin,
                listen = listen, think = think, speak = speak, blink = blink.value, mouth = mouth,
            )
        }
        if (showLabel) {
            Text(
                text = state.label,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            )
        }
    }
}

private fun DrawScope.drawCharm(
    breath: Float, talk: Float, ripple: Float, spin: Float,
    listen: Float, think: Float, speak: Float, blink: Float, mouth: Float,
) {
    val s = min(size.width, size.height)
    val breathScale = 1f + 0.035f * sin(TAU * breath) * (1f - 0.6f * speak)
    val bounce = -0.03f * s * abs(sin(PI.toFloat() * talk)) * speak
    val r = 0.27f * s * breathScale * (1f + 0.05f * listen)
    val c = Offset(size.width / 2f, size.height / 2f - 0.02f * s + bounce)

    // Listening: ripples expanding from the body.
    if (listen > 0.01f) {
        for (i in 0 until 3) {
            val p = (ripple + i / 3f) % 1f
            drawCircle(
                color = Accent.copy(alpha = (1f - p) * 0.5f * listen),
                radius = r * 1.05f + 0.2f * s * p,
                center = c,
                style = Stroke(width = 0.012f * s),
            )
        }
    }

    // Thinking: three dots orbiting the head.
    if (think > 0.01f) {
        for (i in 0 until 3) {
            val a = TAU * (spin + i / 3f)
            drawCircle(
                color = Accent.copy(alpha = think * (0.5f + 0.5f * (i + 1) / 3f)),
                radius = 0.022f * s,
                center = Offset(c.x + cos(a) * r * 1.55f, c.y + sin(a) * r * 1.55f * 0.55f - r * 0.1f),
            )
        }
    }

    // Soft ground shadow, shrinking while the body bounces.
    drawOval(
        color = Color(0xFF2A2250).copy(alpha = 0.55f),
        topLeft = Offset(c.x - r * 0.8f, c.y + r * 1.05f - bounce * 0.5f),
        size = Size(r * 1.6f, r * 0.22f * (1f + bounce / s * 6f)),
    )

    // Tuft on top.
    val tuft = Path().apply {
        moveTo(c.x - r * 0.06f, c.y - r * 0.96f)
        quadraticTo(c.x - r * 0.22f, c.y - r * 1.4f, c.x + r * 0.08f, c.y - r * 1.34f)
        quadraticTo(c.x + r * 0.02f, c.y - r * 1.12f, c.x + r * 0.1f, c.y - r * 0.95f)
        close()
    }
    drawPath(tuft, BodyDark)

    // Body.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(BodyLight, BodyDark),
            center = Offset(c.x - r * 0.25f, c.y - r * 0.35f),
            radius = r * 1.5f,
        ),
        radius = r,
        center = c,
    )

    // Cheeks.
    for (side in listOf(-1f, 1f)) {
        drawCircle(Cheek.copy(alpha = 0.45f), radius = r * 0.13f, center = Offset(c.x + side * r * 0.62f, c.y + r * 0.2f))
    }

    // Eyes: wider while listening, glancing up and aside while thinking, blinking now and then.
    val eyeScale = 1f + 0.25f * listen
    val look = Offset(r * 0.07f * think, -r * 0.09f * think)
    for (side in listOf(-1f, 1f)) {
        val ec = Offset(c.x + side * r * 0.36f + look.x, c.y - r * 0.12f + look.y)
        val rx = r * 0.11f * eyeScale
        val ry = r * 0.16f * eyeScale * blink
        drawOval(Ink, topLeft = Offset(ec.x - rx, ec.y - ry), size = Size(rx * 2f, ry * 2f))
        if (blink > 0.5f) {
            drawCircle(Color.White, radius = r * 0.035f * eyeScale, center = Offset(ec.x + rx * 0.3f, ec.y - ry * 0.35f))
        }
    }

    // Mouth: a small smile at rest, an opening oval while talking.
    val mc = Offset(c.x, c.y + r * 0.36f)
    val smileAlpha = (1f - mouth * 6f).coerceIn(0f, 1f)
    if (smileAlpha > 0f) {
        drawArc(
            color = Ink.copy(alpha = smileAlpha),
            startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(mc.x - r * 0.14f, mc.y - r * 0.12f),
            size = Size(r * 0.28f, r * 0.2f),
            style = Stroke(width = 0.012f * s),
        )
    }
    if (mouth > 0.02f) {
        val w = r * 0.26f * (1f - 0.25f * mouth)
        val h = r * (0.04f + 0.24f * mouth)
        drawOval(MouthDark, topLeft = Offset(mc.x - w / 2f, mc.y - h / 2f), size = Size(w, h))
    }
}
