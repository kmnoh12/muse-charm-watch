package muse.charm.watch

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.wear.compose.material.MaterialTheme
import muse.gadget.Identity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Smoke check that the protocol module links into the app.
        Log.i("MuseCharm", "protocol linked, sample node id ${Identity.generate().nodeId}")
        setContent {
            MaterialTheme {
                // M2 demo: tap cycles the four states. Real triggers (button, STT, TTS) replace this later.
                var state by remember { mutableStateOf(CharmState.IDLE) }
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { state = state.next() } }) {
                    CharmScreen(state)
                }
            }
        }
    }
}
