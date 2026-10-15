package com.guidehub.link

import com.guidehub.core.Clock
import com.guidehub.core.Constants
import com.guidehub.core.EventBus
import com.guidehub.core.LinkId
import com.guidehub.core.LinkState
import com.guidehub.core.LinkStatus
import com.guidehub.core.LogEvent
import com.guidehub.protocol.FrameCodec
import com.guidehub.protocol.FrameHeader
import com.guidehub.protocol.ProtocolException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A decoded camera frame from the headband. */
class Frame(val header: FrameHeader, val jpeg: ByteArray, val receivedTs: Long)

/** Where frames come from: the headband WebSocket, a phone-camera test source, or a recording. */
interface FrameSource {
    val frames: SharedFlow<Frame>
}

/**
 * Frames-only WebSocket server (PRD 7.2). The headband connects to ws://<phone-ip>:<port>/frames and
 * /control with header `Authorization: Bearer <pairing token>`; anything else is rejected during the
 * handshake so no unauthenticated socket stays open on the network.
 */
class WsServer(
    host: String,
    port: Int,
    private val token: String,
    private val clock: Clock,
    private val bus: EventBus,
) : WebSocketServer(InetSocketAddress(host, port)), FrameSource {

    private val _frames = MutableSharedFlow<Frame>(replay = 1, extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val frames: SharedFlow<Frame> = _frames.asSharedFlow()
    @Volatile var framesReceived = 0L; private set
    @Volatile var headbandInfo: String = ""; private set

    init {
        isReuseAddr = true
        connectionLostTimeout = 5
    }

    override fun onWebsocketHandshakeReceivedAsServer(conn: WebSocket, draft: Draft, request: ClientHandshake): ServerHandshakeBuilder {
        val path = request.resourceDescriptor.substringBefore('?')
        if (path != Constants.WS_PATH_FRAMES && path != Constants.WS_PATH_CONTROL) {
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unknown path")
        }
        val auth = request.getFieldValue("Authorization").orEmpty()
        val presented = auth.removePrefix("Bearer ").trim()
        if (!constantTimeEquals(presented, token)) {
            bus.emit(LogEvent("ws", "Rejected unauthenticated connection from ${conn.remoteSocketAddress}", clock.now()))
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized")
        }
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        if (handshake.resourceDescriptor.startsWith(Constants.WS_PATH_FRAMES)) {
            bus.emit(LinkStatus(LinkId.HEADBAND_WIFI, LinkState.CONNECTED, "frames from ${conn.remoteSocketAddress}", clock.now()))
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        if (conn.resourceDescriptor?.startsWith(Constants.WS_PATH_FRAMES) == true) {
            bus.emit(LinkStatus(LinkId.HEADBAND_WIFI, LinkState.DISCONNECTED, "closed ($code)", clock.now()))
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // /control: {"device":"headband","fw":"x.y","caps":["frames","aruco"]}
        try {
            val o = Json.parseToJsonElement(message).jsonObject
            headbandInfo = "device=${o["device"]?.jsonPrimitive?.content} fw=${o["fw"]?.jsonPrimitive?.content} caps=${o["caps"]}"
            bus.emit(LogEvent("ws", "Handshake: $headbandInfo", clock.now()))
            conn.send("""{"ok":true}""")
        } catch (e: Exception) {
            conn.send("""{"ok":false,"error":"bad json"}""")
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val bytes = ByteArray(message.remaining()).also { message.get(it) }
        try {
            val (header, jpeg) = FrameCodec.decode(bytes)
            framesReceived++
            _frames.tryEmit(Frame(header, jpeg, clock.now()))
        } catch (e: ProtocolException) {
            bus.emit(LogEvent("ws", "Bad frame: ${e.message}", clock.now()))
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        bus.emit(LogEvent("ws", "Error: ${ex.message}", clock.now()))
    }

    override fun onStart() {
        bus.emit(LogEvent("ws", "Frame server listening on $address", clock.now()))
    }

    private fun constantTimeEquals(a: String, b: String) =
        b.isNotEmpty() && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
