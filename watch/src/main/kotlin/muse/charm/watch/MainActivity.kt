package muse.charm.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import muse.charm.conversation.CharmState
import muse.gadget.ServiceStatus

private enum class Screen { CHARM, PAIRING }

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as MuseCharmApp).runtime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = runtime
        val controller = runtime.controller

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(Screen.CHARM) }
                val bleState by runtime.ble.state.collectAsState()
                val serviceStatus by runtime.serviceStatus.collectAsState()

                val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                    if (grants.values.all { it }) runtime.startPairing()
                }
                fun beginPairing() {
                    screen = Screen.PAIRING
                    val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
                    if (needed.all { has(it) }) runtime.startPairing() else askBluetooth.launch(needed)
                }

                when (screen) {
                    Screen.PAIRING -> PairingScreen(
                        name = runtime.identity.bleName,
                        bleState = bleState,
                        detail = runtime.ble.detail,
                        hasSdkToken = runtime.hasSdkToken,
                        paired = serviceStatus != ServiceStatus.NOT_PAIRED,
                        onStop = { runtime.stopPairing(); screen = Screen.CHARM },
                    )
                    Screen.CHARM -> CharmWithTalk(serviceStatus, onConnect = ::beginPairing)
                }
            }
        }
    }

    @Composable
    private fun CharmWithTalk(serviceStatus: ServiceStatus, onConnect: () -> Unit) {
        val controller = runtime.controller
        val state by controller.state.collectAsState()
        val error by controller.error.collectAsState()
        var micGranted by remember { mutableStateOf(has(Manifest.permission.RECORD_AUDIO)) }
        var micDenied by remember { mutableStateOf(false) }
        val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            micGranted = it
            micDenied = !it
        }
        val showConnectChip = serviceStatus == ServiceStatus.NOT_PAIRED && state == CharmState.IDLE && error == null && !micDenied
        val caption = when {
            micDenied -> "마이크 권한이 필요해요"
            error != null -> error
            serviceStatus == ServiceStatus.CONNECTING -> "뮤즈에 연결 중…"
            showConnectChip -> "" // the chip sits where the caption would be
            else -> null
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
            // The Jollybot frames when they load; the drawn charm is the fallback.
            val context = LocalContext.current
            val sprites by produceState<JollySprites?>(null) { value = JollySprites.load(context) }
            val loaded = sprites
            if (loaded != null) JollyScreen(state, loaded, caption = caption) else CharmScreen(state, caption = caption)

            if (showConnectChip) {
                CompactChip(
                    onClick = onConnect,
                    label = { Text("뮤즈 연결", fontSize = 11.sp) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                )
            }
        }
    }

    private fun has(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onStop() {
        super.onStop()
        runtime.controller.cancel() // never keep the microphone open in the background
    }
}

@Composable
private fun PairingScreen(
    name: String,
    bleState: BlePeripheral.State,
    detail: String,
    hasSdkToken: Boolean,
    paired: Boolean,
    onStop: () -> Unit,
) {
    val status = when {
        paired -> "연결됐어요"
        bleState == BlePeripheral.State.STARTING -> "준비 중…"
        bleState == BlePeripheral.State.ADVERTISING -> "뮤즈 앱에서 찾는 중"
        bleState == BlePeripheral.State.CONNECTED -> "폰과 연결됨, 설정 중…"
        bleState == BlePeripheral.State.UNSUPPORTED -> "지원 안 함: $detail"
        bleState == BlePeripheral.State.ERROR -> "오류: $detail"
        else -> "멈춤"
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("뮤즈 연결", fontSize = 15.sp, color = Color.White, textAlign = TextAlign.Center)
        Text("폰의 뮤즈 앱 → 설정 → 기기 → 개발자 모드 켜기 → 기기 추가", fontSize = 10.sp, color = Color(0xFFB9B9C8), textAlign = TextAlign.Center)
        Text(name, fontSize = 12.sp, color = Color(0xFFB9A6FF), textAlign = TextAlign.Center)
        Text(status, fontSize = 11.sp, color = Color.White, textAlign = TextAlign.Center)
        if (!hasSdkToken) Text("SDK 토큰이 앱에 없어요", fontSize = 10.sp, color = Color(0xFFFF8A8A), textAlign = TextAlign.Center)
        CompactChip(onClick = onStop, label = { Text(if (paired) "닫기" else "중지", fontSize = 11.sp) })
    }
}
