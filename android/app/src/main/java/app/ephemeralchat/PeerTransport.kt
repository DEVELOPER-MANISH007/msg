package app.ephemeralchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers

internal data class ReceivedFile(val id: String, val senderId: String, val name: String, val mediaType: String, val bytes: ByteArray)

internal class PeerTransport(
    context: Context,
    private val apiBase: String,
    private val session: Session,
    private val client: OkHttpClient,
    private val onStatus: (String) -> Unit,
    private val onFingerprint: (String) -> Unit,
    private val onVerified: (Boolean) -> Unit,
    private val onIncomingText: (String, String) -> Unit,
    private val onLocalText: (String, String) -> Unit,
    private val onDelivered: (String) -> Unit,
    private val onDeliveryFailed: (String) -> Unit,
    private val onPurge: (String) -> Unit,
    private val onFileProgress: (String, Int, Boolean) -> Unit,
    private val onReceivedFile: (ReceivedFile) -> Unit,
    private val onIncomingCall: (Boolean) -> Unit,
    private val onCallState: (String) -> Unit,
    private val onLocalVideo: (VideoTrack?) -> Unit,
    private val onRemoteVideo: (VideoTrack?) -> Unit,
) : WebSocketListener(), PeerConnection.Observer {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var signaling: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var data: DataChannel? = null
    private var audioTransceiver: RtpTransceiver? = null
    private var videoTransceiver: RtpTransceiver? = null
    private var egl: EglBase? = null
    private var localAudio: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var camera: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTexture: SurfaceTextureHelper? = null
    private var keyPair = Crypto.generate()
    @Volatile private var sharedSecret: ByteArray? = null
    @Volatile private var fingerprint = ""
    @Volatile private var localConfirmed = false
    @Volatile private var peerConfirmed = false
    @Volatile private var dataReady = false
    @Volatile private var initialOfferSent = false
    private var callRequestedVideo = false
    private var callActive = false
    private var pendingRemoteDescription: SessionDescription? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private val deliveredPending = ConcurrentHashMap.newKeySet<String>()
    private val canceledTransfers = ConcurrentHashMap.newKeySet<String>()
    private val incomingFiles = ConcurrentHashMap<String, IncomingFile>()

    private data class IncomingFile(
        val id: String,
        val name: String,
        val mediaType: String,
        val size: Int,
        val chunks: Int,
        val senderId: String,
        val output: ByteArrayOutputStream = ByteArrayOutputStream(),
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
        var nextChunk: Int = 0,
    )

    val eglContext: EglBase.Context? get() = egl?.eglBaseContext
    val localVideoTrack: VideoTrack? get() = localVideo
    val remoteVideoTrack: VideoTrack? get() = remoteVideo
    val isVerified: Boolean get() = dataReady && localConfirmed && peerConfirmed
    val isChannelOpen: Boolean get() = data?.state() == DataChannel.State.OPEN

    fun connect() {
        if (closed.get()) return
        try {
            initializeRtc()
            val url = apiBase.trimEnd('/').replaceFirst("https://", "wss://") + "/v1/rooms/${session.room}/socket"
            signaling = client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer ${session.credential}").build(), this)
            postStatus("Signaling connected; waiting for the other participant.")
        } catch (e: Exception) {
            postStatus("Could not start peer connection: ${e.message ?: "check WebRTC availability"}")
        }
    }

    fun reconnect() {
        if (closed.get()) return
        postStatus("Reconnecting room signaling; messages are not queued.")
        signaling?.close(1000, "Reconnect")
        initialOfferSent = false
        peer?.restartIce()
        val url = apiBase.trimEnd('/').replaceFirst("https://", "wss://") + "/v1/rooms/${session.room}/socket"
        signaling = client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer ${session.credential}").build(), this)
    }

    private fun initializeRtc() {
        if (peer != null) return
        if (factory == null) {
            synchronized(FACTORY_LOCK) {
                if (!initialized.getAndSet(true)) PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            }
        }
        egl = EglBase.create()
        val ice = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val configuration = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        peer = requireNotNull(factory).createPeerConnection(configuration, this) ?: error("WebRTC could not create a peer connection")
        val receiveOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        audioTransceiver = peer?.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, receiveOnly)
        videoTransceiver = peer?.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, receiveOnly)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        postStatus("Signaling online. Looking for a direct peer connection; no TURN relay is configured.")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val packet = JSONObject(text)
            when (packet.optString("type")) {
                "peer-ready" -> {
                    if (session.initiator && !initialOfferSent && peer?.signalingState() == PeerConnection.SignalingState.STABLE) {
                        if (data == null) peer?.createDataChannel("ephemeral-direct", DataChannel.Init())?.let(::registerDataChannel)
                        initialOfferSent = true
                        createDescription("offer")
                    } else postStatus("Peer found; establishing the encrypted direct connection…")
                }
                "offer" -> setRemoteAndAnswer(packet.getString("sdp"), "answer")
                "answer" -> setRemote(packet.getString("sdp"), SessionDescription.Type.ANSWER)
                "ice" -> receiveIce(packet)
                "call-offer" -> setRemoteAndAnswer(packet.getString("sdp"), "call-answer")
                "call-answer" -> setRemote(packet.getString("sdp"), SessionDescription.Type.ANSWER)
                "purge-sender" -> post { onPurge(packet.optString("senderId")) }
                "room-closed" -> postStatus("Room closed by the other participant.")
                "error" -> postStatus("Room signaling rejected a packet (${packet.optString("code")}).")
            }
        } catch (_: Exception) { postStatus("Invalid or unusable signaling packet.") }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (!closed.get()) postStatus("Room signaling disconnected. Direct delivery may continue; reconnect to coordinate again.")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (!closed.get()) postStatus("Room signaling closed; direct messages are available only while the peer channel remains connected.")
    }

    private fun sendSignal(packet: JSONObject) {
        if (signaling?.send(packet.toString()) != true) postStatus("Signaling is unavailable; peer negotiation was not queued.")
    }

    private fun createDescription(type: String) {
        val pc = peer ?: return
        val observer = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) { postStatus("Could not create the peer offer."); return }
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() { sendSignal(JSONObject().put("type", type).put("sdp", description.description)) }
                    override fun onSetFailure(error: String?) { postStatus("Could not apply local peer description: ${error.orEmpty()}") }
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onCreateFailure(p0: String?) = Unit
                }, description)
            }
            override fun onCreateFailure(error: String?) { postStatus("Could not create peer offer: ${error.orEmpty()}") }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) { postStatus("Peer description failed: ${error.orEmpty()}") }
        }
        if (type.endsWith("answer")) pc.createAnswer(observer, MediaConstraints()) else pc.createOffer(observer, MediaConstraints())
    }

    private fun setRemote(sdp: String, type: SessionDescription.Type) {
        val pc = peer ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { drainIce(); postStatus("Peer description accepted; checking direct network path…") }
            override fun onSetFailure(error: String?) { postStatus("Peer description rejected: ${error.orEmpty()}") }
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onCreateFailure(p0: String?) = Unit
        }, SessionDescription(type, sdp))
    }

    private fun setRemoteAndAnswer(sdp: String, answerType: String) {
        val pc = peer ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { drainIce(); createDescription(answerType) }
            override fun onSetFailure(error: String?) { postStatus("Incoming peer offer rejected: ${error.orEmpty()}") }
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onCreateFailure(p0: String?) = Unit
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun receiveIce(packet: JSONObject) {
        val candidate = IceCandidate(packet.optString("sdpMid").takeIf { it.isNotBlank() }, packet.getInt("sdpMLineIndex"), packet.getString("candidate"))
        if (peer?.remoteDescription == null) synchronized(pendingIce) { pendingIce += candidate } else peer?.addIceCandidate(candidate)
    }

    private fun drainIce() {
        val queued = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
        queued.forEach { peer?.addIceCandidate(it) }
    }

    override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> postStatus("Peer-to-peer transport connected. Verify the fingerprint before messaging or calling.")
            PeerConnection.IceConnectionState.DISCONNECTED -> postStatus("Peer connection interrupted. No TURN relay is available; retry when both devices are online.")
            PeerConnection.IceConnectionState.FAILED -> {
                    postStatus("Direct connection failed. This network may require a TURN relay, which is not configured and will not be charged.")
                    deliveredPending.toList().forEach { id -> post { onDeliveryFailed(id) } }
                    deliveredPending.clear()
                if (localAudio != null || localVideo != null || callActive) {
                    callActive = false
                    stopLocalMedia()
                    post { onCallState("Call ended because direct connectivity failed") }
                }
                post { onVerified(false) }
                data?.close()
                }
            PeerConnection.IceConnectionState.CLOSED -> Unit
            else -> Unit
        }
    }
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
    override fun onIceCandidate(candidate: IceCandidate?) {
        if (candidate == null) return
        sendSignal(JSONObject().put("type", "ice").put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex))
    }
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
    override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
    override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
    override fun onDataChannel(channel: DataChannel?) { channel?.let(::registerDataChannel) }
    override fun onRenegotiationNeeded() = Unit
    override fun onTrack(transceiver: RtpTransceiver?) {
        val track = transceiver?.receiver?.track()
        if (track is VideoTrack) { remoteVideo = track; post { onRemoteVideo(track) } }
    }

    private fun registerDataChannel(channel: DataChannel) {
        data = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                dataReady = channel.state() == DataChannel.State.OPEN
                if (dataReady) {
                    onDataChannelOpen()
                } else if (channel.state() == DataChannel.State.CLOSED) {
                    post { onVerified(false) }
                    deliveredPending.toList().forEach { id -> post { onDeliveryFailed(id) } }
                    deliveredPending.clear()
                    if (data === channel) data = null
                    postStatus("Direct channel closed. Messages were not queued.")
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || buffer.data.remaining() > MAX_DATA_PACKET_BYTES) { postStatus("Unsupported or oversized direct packet received."); return }
                val raw = ByteArray(buffer.data.remaining()); buffer.data.get(raw)
                try { handleDataPacket(JSONObject(String(raw, Charsets.UTF_8))) } catch (_: Exception) { postStatus("Could not validate an incoming direct message.") }
            }
        })
        if (channel.state() == DataChannel.State.OPEN) onDataChannelOpen()
    }

    private fun onDataChannelOpen() {
        sendControl(JSONObject().put("type", "key-exchange").put("publicKey", Base64.getEncoder().encodeToString(keyPair.public.encoded)))
        postStatus("Direct data channel open; exchanging ephemeral keys for out-of-band verification.")
    }

    private fun handleDataPacket(packet: JSONObject) {
        when (packet.optString("type")) {
            "key-exchange" -> {
                val peerKey = Base64.getDecoder().decode(packet.getString("publicKey"))
                sharedSecret?.fill(0)
                sharedSecret = Crypto.shared(keyPair, peerKey, session.room)
                fingerprint = Crypto.fingerprint(requireNotNull(sharedSecret))
                localConfirmed = false; peerConfirmed = false
                post { onFingerprint(fingerprint); onVerified(false) }
            }
            "verify-auth" -> {
                val key = sharedSecret ?: return
                val expected = Crypto.authenticationTag(key, session.room)
                val provided = Base64.getDecoder().decode(packet.getString("tag"))
                peerConfirmed = MessageDigest.isEqual(expected, provided)
                post { onVerified(isVerified); if (!peerConfirmed) onStatus("Peer key verification failed; do not send messages or call.") }
            }
            "text" -> {
                if (!isVerified) return
                val key = sharedSecret ?: return
                val id = packet.getString("id")
                val text = Crypto.decrypt(key, session.room, packet.getString("ciphertext"))
                post { onIncomingText(packet.optString("senderId", "peer"), text) }
                sendControl(JSONObject().put("type", "ack").put("ciphertext", Crypto.encrypt(key, session.room, "ack:$id")))
            }
            "ack" -> {
                if (!isVerified) return
                val key = sharedSecret ?: return
                val clear = Crypto.decrypt(key, session.room, packet.getString("ciphertext"))
                if (clear.startsWith("ack:")) { val id = clear.removePrefix("ack:"); deliveredPending.remove(id); post { onDelivered(id) } }
            }
            "file-start" -> startIncomingFile(packet)
            "file-chunk" -> receiveFileChunk(packet)
            "file-end" -> finishIncomingFile(packet)
            "file-cancel" -> { val id = packet.optString("id"); incomingFiles.remove(id); post { onFileProgress(id, 0, false) } }
            "call-request" -> if (isVerified) post { onIncomingCall(packet.optBoolean("video")) }
            "call-accept" -> if (isVerified) { callActive = true; post { onCallState("Call connected"); createDescription("call-offer") } }
            "call-reject" -> { callActive = false; stopLocalMedia(); post { onCallState("Call declined") } }
            "call-end" -> { callActive = false; stopLocalMedia(); post { onCallState("Call ended by peer") } }
        }
    }

    fun confirmFingerprint() {
        if (sharedSecret == null || fingerprint.isBlank()) return
        localConfirmed = true
        sendControl(JSONObject().put("type", "verify-auth").put("tag", Base64.getEncoder().encodeToString(Crypto.authenticationTag(requireNotNull(sharedSecret), session.room))))
        post { onVerified(isVerified); postStatus(if (isVerified) "Fingerprint verified by both peers; direct messages and calls are enabled." else "Confirmation sent; waiting for the peer to verify the same fingerprint.") }
    }

    fun sendText(text: String): String? {
        if (!isVerified) { postStatus("Compare and verify the peer fingerprint before sending."); return null }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) { postStatus("Text is limited to 32 KiB per message."); return null }
        val key = sharedSecret ?: return null
        val id = UUID.randomUUID().toString()
        val sent = sendControl(JSONObject().put("type", "text").put("id", id).put("senderId", session.participant).put("ciphertext", Crypto.encrypt(key, session.room, text)))
        if (!sent) { postStatus("Direct peer is unavailable. Text was not stored or queued."); return null }
        deliveredPending += id
        post { onLocalText(id, text) }
        return id
    }

    suspend fun sendFile(name: String, mediaType: String, size: Long, input: InputStream, transferId: String, onProgress: (Int) -> Unit): String = withContext(Dispatchers.IO) {
        require(isVerified) { "Verify the peer fingerprint before sending files" }
        val chunks = TransferManifest.chunkCount(size)
        val start = JSONObject().put("id", transferId).put("name", name.take(100)).put("mediaType", mediaType.take(128)).put("size", size).put("chunks", chunks)
        try {
            sendEncryptedFileControl("file-start", transferId, start.toString())
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(FILE_CHUNK_BYTES)
            var sentBytes = 0L
            var index = 0
            while (true) {
                if (closed.get() || canceledTransfers.contains(transferId)) throw IllegalStateException("Transfer cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                sentBytes += count
                require(sentBytes <= size && sentBytes <= MAX_FILE_BYTES) { "File changed or exceeds 10 MiB" }
                digest.update(buffer, 0, count)
                val encryptedChunk = Crypto.encryptBytes(requireNotNull(sharedSecret), session.room, buffer.copyOf(count))
                val json = JSONObject().put("type", "file-chunk").put("id", transferId).put("senderId", session.participant).put("index", index++).put("ciphertext", Base64.getEncoder().encodeToString(encryptedChunk))
                if (!sendWithBackpressure(json)) throw IllegalStateException("Peer disconnected; file transfer stopped")
                val percent = ((sentBytes * 100) / size.coerceAtLeast(1)).toInt()
                post { onFileProgress(transferId, percent, true); onProgress(percent) }
            }
            require(sentBytes == size && index == chunks) { "File size changed during transfer" }
            val end = JSONObject().put("sha256", Base64.getEncoder().encodeToString(digest.digest()))
            sendEncryptedFileControl("file-end", transferId, end.toString())
            post { onFileProgress(transferId, 100, true); onProgress(100) }
            transferId
        } catch (failure: Exception) {
            canceledTransfers += transferId
            sendControl(JSONObject().put("type", "file-cancel").put("id", transferId))
            throw failure
        } finally {
            canceledTransfers.remove(transferId)
        }
    }

    private suspend fun sendEncryptedFileControl(type: String, id: String, value: String) {
        val encrypted = Crypto.encrypt(requireNotNull(sharedSecret), session.room, value)
        if (!sendWithBackpressure(JSONObject().put("type", type).put("id", id).put("senderId", session.participant).put("ciphertext", encrypted))) throw IllegalStateException("Peer is unavailable; file was not stored")
    }

    private suspend fun sendWithBackpressure(packet: JSONObject): Boolean {
        val channel = data ?: return false
        val deadline = System.nanoTime() + BACKPRESSURE_TIMEOUT_NANOS
        while (!closed.get() && channel.state() == DataChannel.State.OPEN && channel.bufferedAmount() > MAX_BUFFERED_BYTES) {
            if (System.nanoTime() >= deadline) throw IllegalStateException("Peer stopped accepting data; transfer cancelled")
            delay(20)
        }
        return !closed.get() && sendRaw(packet)
    }

    fun cancelTransfer(id: String) {
        canceledTransfers += id
        sendControl(JSONObject().put("type", "file-cancel").put("id", id))
    }

    fun clearSender(senderId: String) {
        incomingFiles.entries.removeIf { entry ->
            if (entry.value.senderId == senderId) { entry.value.output.reset(); true } else false
        }
    }

    private fun startIncomingFile(packet: JSONObject) {
        if (!isVerified) return
        if (incomingFiles.size >= MAX_CONCURRENT_INCOMING_FILES) { sendControl(JSONObject().put("type", "file-cancel").put("id", packet.optString("id"))); return }
        val key = sharedSecret ?: return
        val id = packet.getString("id")
        val metadata = JSONObject(Crypto.decrypt(key, session.room, packet.getString("ciphertext")))
        val size = metadata.getLong("size")
        val chunks = metadata.getInt("chunks")
        require(chunks in 0..MAX_FILE_CHUNKS)
        TransferManifest.validate(size, chunks)
        incomingFiles[id] = IncomingFile(id, metadata.getString("name").take(100), metadata.getString("mediaType").take(128), size.toInt(), chunks, packet.optString("senderId", "peer"))
        post { onFileProgress(id, 0, false) }
    }

    private fun receiveFileChunk(packet: JSONObject) {
        val transfer = incomingFiles[packet.getString("id")] ?: return
        if (!isVerified || packet.getInt("index") != transfer.nextChunk) { incomingFiles.remove(transfer.id); sendControl(JSONObject().put("type", "file-cancel").put("id", transfer.id)); return }
        val chunk = Crypto.decryptBytes(requireNotNull(sharedSecret), session.room, Base64.getDecoder().decode(packet.getString("ciphertext")))
        if (transfer.output.size() + chunk.size > transfer.size || transfer.output.size() + chunk.size > MAX_FILE_BYTES) { incomingFiles.remove(transfer.id); return }
        transfer.digest.update(chunk)
        transfer.output.write(chunk)
        transfer.nextChunk++
        val percent = if (transfer.size == 0) 100 else transfer.output.size() * 100 / transfer.size
        post { onFileProgress(transfer.id, percent, false) }
    }

    private fun finishIncomingFile(packet: JSONObject) {
        val transfer = incomingFiles.remove(packet.getString("id")) ?: return
        val metadata = JSONObject(Crypto.decrypt(requireNotNull(sharedSecret), session.room, packet.getString("ciphertext")))
        val expectedHash = Base64.getDecoder().decode(metadata.getString("sha256"))
        val actualHash = transfer.digest.digest()
        require(transfer.nextChunk == transfer.chunks && transfer.output.size() == transfer.size && MessageDigest.isEqual(expectedHash, actualHash)) { "File integrity check failed" }
        val result = ReceivedFile(transfer.id, transfer.senderId, transfer.name, transfer.mediaType, transfer.output.toByteArray())
        post { onReceivedFile(result); onFileProgress(result.id, 100, false) }
    }

    fun requestCall(video: Boolean) {
        if (!isVerified) { post { onCallState("Verify the peer before calling") }; return }
        callRequestedVideo = video
        try {
            attachLocalMedia(video)
            if (!sendControl(JSONObject().put("type", "call-request").put("video", video))) throw IllegalStateException("Peer is unavailable")
            post { onCallState("Calling; waiting for peer acceptance…") }
        } catch (e: Exception) { post { onCallState("Could not start call: ${e.message}") } }
    }

    fun acceptCall(video: Boolean) {
        try {
            attachLocalMedia(video)
            if (!sendControl(JSONObject().put("type", "call-accept").put("video", video))) throw IllegalStateException("Peer disconnected")
            callActive = true
            post { onCallState("Accepted; connecting call…") }
        } catch (e: Exception) { post { onCallState("Could not accept call: ${e.message}") } }
    }

    fun rejectCall() { sendControl(JSONObject().put("type", "call-reject")); post { onCallState("Call declined") } }
    fun hangUp() { sendControl(JSONObject().put("type", "call-end")); callActive = false; stopLocalMedia(); post { onCallState("Call ended") } }
    fun endCallForLifecycle() {
        if (callActive || localAudio != null || localVideo != null) hangUp()
    }
    fun setMicrophoneEnabled(enabled: Boolean) { localAudio?.setEnabled(enabled) }
    fun setCameraEnabled(enabled: Boolean) { localVideo?.setEnabled(enabled) }
    fun switchCamera() { camera?.switchCamera(null) }

    private fun attachLocalMedia(video: Boolean) {
        if (localAudio == null) {
            val audioSource = requireNotNull(factory).createAudioSource(MediaConstraints())
            this.audioSource = audioSource
            localAudio = requireNotNull(factory).createAudioTrack("ephemeral-audio", audioSource).also { it.setEnabled(true) }
            requireNotNull(audioTransceiver).sender.setTrack(localAudio, false)
            audioTransceiver?.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        }
        if (video && localVideo == null) {
            val enumerator = Camera2Enumerator(appContext)
            val device = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: error("No camera is available")
            val capturer = enumerator.createCapturer(device, null) ?: error("Camera could not start")
            val helper = SurfaceTextureHelper.create("ephemeral-camera", requireNotNull(egl).eglBaseContext)
            val source = requireNotNull(factory).createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, appContext, source.capturerObserver)
            capturer.startCapture(640, 480, 24)
            camera = capturer; surfaceTexture = helper; videoSource = source
            localVideo = requireNotNull(factory).createVideoTrack("ephemeral-video", source).also { it.setEnabled(true) }
            requireNotNull(videoTransceiver).sender.setTrack(localVideo, false)
            videoTransceiver?.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            post { onLocalVideo(localVideo) }
        }
    }

    private fun stopLocalMedia() {
        runCatching { camera?.stopCapture() }
        camera?.dispose(); camera = null
        surfaceTexture?.dispose(); surfaceTexture = null
        localVideo?.setEnabled(false); videoTransceiver?.sender?.setTrack(null, false); localVideo?.dispose(); localVideo = null
        localAudio?.setEnabled(false); audioTransceiver?.sender?.setTrack(null, false); localAudio?.dispose(); localAudio = null
        audioSource?.dispose(); audioSource = null
        videoTransceiver?.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        audioTransceiver?.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        videoSource?.dispose(); videoSource = null
        callActive = false
        post { onLocalVideo(null) }
    }

    private fun sendControl(packet: JSONObject): Boolean {
        if (closed.get()) return false
        return sendRaw(packet)
    }

    private fun sendRaw(packet: JSONObject): Boolean {
        val channel = data ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(packet.toString().toByteArray(Charsets.UTF_8)), false))
    }

    fun attachRemoteRenderer(renderer: org.webrtc.VideoSink) { remoteVideo?.addSink(renderer) }
    fun detachRemoteRenderer(renderer: org.webrtc.VideoSink) { remoteVideo?.removeSink(renderer) }
    fun attachLocalRenderer(renderer: org.webrtc.VideoSink) { localVideo?.addSink(renderer) }
    fun detachLocalRenderer(renderer: org.webrtc.VideoSink) { localVideo?.removeSink(renderer) }

    fun leave() {
        if (!closed.compareAndSet(false, true)) return
        signaling?.close(1000, "Exited"); signaling = null
        data?.unregisterObserver(); data?.close(); data?.dispose(); data = null
        stopLocalMedia()
        sharedSecret?.fill(0); sharedSecret = null; keyPair.private.destroy()
        incomingFiles.clear(); canceledTransfers.clear()
        peer?.close(); peer?.dispose(); peer = null
        factory?.dispose(); factory = null
        egl?.release(); egl = null
        remoteVideo?.dispose(); remoteVideo = null
    }

    private fun postStatus(message: String) = post { onStatus(message) }
    private fun post(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    companion object {
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        const val FILE_CHUNK_BYTES = 12 * 1024
        const val MAX_FILE_CHUNKS = 900
        private const val MAX_CONCURRENT_INCOMING_FILES = 1
        private const val MAX_BUFFERED_BYTES = 256 * 1024L
        private const val BACKPRESSURE_TIMEOUT_NANOS = 15_000_000_000L
        private const val MAX_DATA_PACKET_BYTES = 64 * 1024
        private const val MAX_TEXT_BYTES = 32 * 1024
        private val FACTORY_LOCK = Any()
        private val initialized = AtomicBoolean(false)
    }
}

internal object TransferManifest {
    fun chunkCount(size: Long): Int {
        require(size in 0..PeerTransport.MAX_FILE_BYTES) { "Files are limited to 10 MiB" }
        val count = if (size == 0L) 0 else ((size + PeerTransport.FILE_CHUNK_BYTES - 1) / PeerTransport.FILE_CHUNK_BYTES).toInt()
        require(count <= PeerTransport.MAX_FILE_CHUNKS) { "File contains too many chunks" }
        return count
    }

    fun validate(size: Long, chunks: Int) {
        require(chunks == chunkCount(size)) { "File chunk count does not match its size" }
    }
}
