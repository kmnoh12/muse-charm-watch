package muse.charm.watch

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.wear.compose.material.Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import muse.charm.conversation.CharmState

/**
 * The Jollybot animation at its native size: 75 frames of 56x59 pixels in one 16 KB sprite sheet
 * (see tools/extract_jolly_native.py), drawn enlarged with nearest-neighbour scaling.
 */
class JollySprites(val sheet: ImageBitmap) {
    companion object {
        const val FRAME_MS = 40L
        const val FRAME_COUNT = 75
        const val FRAME_W = 56
        const val FRAME_H = 59
        private const val COLUMNS = 15

        /** Arms up and hearts: used while speaking. */
        val HAPPY: List<Int> = (13..49).toList()

        /** The calm loop: the settle-down tail of the animation, then its opening. */
        val CALM: List<Int> = (50..74).toList() + (0..12).toList()

        suspend fun load(context: Context): JollySprites? = withContext(Dispatchers.IO) {
            try {
                val bitmap = context.assets.open("jolly/jolly_sheet.png").use { BitmapFactory.decodeStream(it) }
                JollySprites(bitmap.asImageBitmap())
            } catch (e: Exception) {
                null
            }
        }
    }

    /** The frame index to show [sinceStateMs] after the state began. Speaking starts at the arms-up frame. */
    fun frameIndexFor(state: CharmState, sinceStateMs: Long): Int {
        val step = (sinceStateMs / FRAME_MS).toInt()
        val loop = if (state == CharmState.SPEAKING) HAPPY else CALM
        return loop[step % loop.size]
    }

    fun sourceOffset(frame: Int) = IntOffset((frame % COLUMNS) * FRAME_W, (frame / COLUMNS) * FRAME_H)
}

private val Accent = Color(0xFFB9A6FF)
private const val TAU = (2 * PI).toFloat()

/**
 * The charm as the Jollybot animation: calm loop for idle, listening and thinking (with ripples or
 * orbiting dots on top), the happy loop while speaking.
 */
@Composable
fun JollyScreen(
    state: CharmState,
    sprites: JollySprites,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    var now by remember { mutableLongStateOf(0L) }
    var stateStart by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { now = it / 1_000_000 }
    }
    LaunchedEffect(state) { stateStart = now }

    val frame = sprites.frameIndexFor(state, (now - stateStart).coerceAtLeast(0))
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val s = min(size.width, size.height)
            // Whole-pixel scale keeps every art pixel the same size on screen.
            val scale = maxOf(1, (s * 0.88f / JollySprites.FRAME_H).toInt())
            val w = JollySprites.FRAME_W * scale
            val h = JollySprites.FRAME_H * scale
            drawImage(
                sprites.sheet,
                srcOffset = sprites.sourceOffset(frame),
                srcSize = IntSize(JollySprites.FRAME_W, JollySprites.FRAME_H),
                dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2 - 0.02f * s).toInt()),
                dstSize = IntSize(w, h),
                filterQuality = FilterQuality.None, // keep the pixels crisp
            )
            // The frames have an opaque black background, so effects are drawn on top, around the body.
            when (state) {
                CharmState.LISTENING -> drawRipples(s, now)
                CharmState.THINKING -> drawOrbit(s, now)
                else -> {}
            }
        }
        Text(
            text = caption ?: state.label,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        )
    }
}

private fun DrawScope.drawRipples(s: Float, nowMs: Long) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val phase = (nowMs % 1800L) / 1800f
    for (i in 0 until 3) {
        val p = (phase + i / 3f) % 1f
        drawCircle(
            color = Accent.copy(alpha = (1f - p) * 0.55f),
            radius = 0.34f * s + 0.16f * s * p,
            center = c,
            style = Stroke(width = 0.012f * s),
        )
    }
}

private fun DrawScope.drawOrbit(s: Float, nowMs: Long) {
    val c = Offset(size.width / 2f, size.height / 2f - 0.04f * s)
    val spin = (nowMs % 1400L) / 1400f
    for (i in 0 until 3) {
        val a = TAU * (spin + i / 3f)
        drawCircle(
            color = Accent.copy(alpha = 0.5f + 0.5f * (i + 1) / 3f),
            radius = 0.022f * s,
            center = Offset(c.x + cos(a) * 0.43f * s, c.y + sin(a) * 0.24f * s),
        )
    }
}
