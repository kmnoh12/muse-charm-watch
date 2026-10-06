package muse.charm.watch

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.suspendCancellableCoroutine
import muse.gadget.DEFAULT_USER_AGENT
import muse.gadget.UpgradeRejected
import muse.gadget.WsConnection
import muse.gadget.WsConnector
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** WebSocket transport for the Noise session, on OkHttp (pings keep the connection alive). */
class OkHttpConnector(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // the control stream is long-lived
        .build(),
    private val userAgent: String = DEFAULT_USER_AGENT,
) : WsConnector {
    override suspend fun connect(url: String, headers: Map<String, String>): WsConnection =
        suspendCancellableCoroutine { cont ->
            val inbox = Channel<ByteArray>(Channel.UNLIMITED)
            val request = Request.Builder().url(url).header("User-Agent", userAgent)
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            var opened = false
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    cont.resume(OkHttpConnection(webSocket, inbox))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    inbox.trySend(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    inbox.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (opened) {
                        inbox.close(t)
                    } else {
                        val status = response?.code
                        cont.resumeWithException(if (status == 401 || status == 403) UpgradeRejected(status) else t)
                    }
                }
            })
            cont.invokeOnCancellation { socket.cancel() }
        }
}

private class OkHttpConnection(private val socket: WebSocket, private val inbox: Channel<ByteArray>) : WsConnection {
    override suspend fun send(data: ByteArray) {
        if (!socket.send(data.toByteString())) throw IOException("websocket closed")
    }

    override suspend fun receive(): ByteArray = try {
        inbox.receive()
    } catch (e: ClosedReceiveChannelException) {
        throw IOException("websocket closed")
    }

    override suspend fun close() {
        socket.close(1000, null)
    }
}
