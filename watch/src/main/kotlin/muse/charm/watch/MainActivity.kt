package muse.charm.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material.MaterialTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import muse.charm.conversation.ConversationController

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var speaker: AndroidSpeaker
    private lateinit var controller: ConversationController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = AndroidSpeaker(this)
        // Until the watch is paired with a Muse account, a demo channel answers instead of the real one.
        controller = ConversationController(scope, DemoChannel(scope), AndroidRecorder(), speaker, System::currentTimeMillis)
        controller.start()

        setContent {
            MaterialTheme {
                val state by controller.state.collectAsState()
                val error by controller.error.collectAsState()
                var micGranted by remember { mutableStateOf(hasMic()) }
                var micDenied by remember { mutableStateOf(false) }
                val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    micGranted = it
                    micDenied = !it
                }
                Box(
                    Modifier.fillMaxSize().pointerInput(micGranted) {
                        detectTapGestures(onPress = {
                            if (!micGranted) {
                                askMic.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                // Hold to talk; letting go sends it. Pressing during a reply cuts it off.
                                controller.pressStart()
                                tryAwaitRelease()
                                controller.pressEnd()
                            }
                        })
                    },
                ) {
                        val caption = if (micDenied) "마이크 권한이 필요해요" else error
                    // The Jollybot frames when they load; the drawn charm is the fallback.
                    val context = LocalContext.current
                    val sprites by produceState<JollySprites?>(null) { value = JollySprites.load(context) }
                    val loaded = sprites
                    if (loaded != null) JollyScreen(state, loaded, caption = caption) else CharmScreen(state, caption = caption)
                }
            }
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onStop() {
        super.onStop()
        controller.cancel() // never keep the microphone open in the background
    }

    override fun onDestroy() {
        scope.cancel()
        speaker.shutdown()
        super.onDestroy()
    }
}
