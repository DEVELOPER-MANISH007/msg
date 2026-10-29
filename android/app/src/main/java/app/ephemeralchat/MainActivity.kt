package app.ephemeralchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.IOException
import java.net.URI
import android.provider.OpenableColumns
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme { ChatApp() } }
    }
}

internal data class Session(val room: String, val participant: String, val credential: String, val invitation: String?, val initiator: Boolean)
internal data class Line(
    val sender: String,
    val text: String,
    val mine: Boolean,
    val id: String = UUID.randomUUID().toString(),
    val status: String = "",
    val attachmentId: String? = null,
    val fileName: String? = null,
    val mediaType: String? = null,
    val bytes: ByteArray? = null,
)
internal fun purgeMessagesFrom(lines: List<Line>, senderId: String): List<Line> = lines.filter { line ->
    if (line.sender == senderId) { line.bytes?.fill(0); false } else true
}
internal fun isUsableWorkerEndpoint(value: String): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host.orEmpty()
    uri.scheme.equals("https", ignoreCase = true) && host.isNotBlank() &&
        (uri.path.isNullOrEmpty() || uri.path == "/") && uri.query == null && uri.fragment == null &&
        !host.contains("REPLACE_WITH", ignoreCase = true) && !host.contains("YOUR_WORKER", ignoreCase = true)
}.getOrDefault(false)

@Composable
private fun ChatApp() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val client = remember { OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build() }
    val api = BuildConfig.API_BASE.trimEnd('/')
    val endpointConfigured = isUsableWorkerEndpoint(api)
    var session by remember { mutableStateOf<Session?>(null) }
    var peer by remember { mutableStateOf<PeerTransport?>(null) }
    var status by remember { mutableStateOf(if (endpointConfigured) "Anonymous peer chat · messages and media are not stored." else "Worker endpoint not configured. Build with -PchatApiBase=https://<worker>.workers.dev.") }
    var callStatus by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(emptyList<Line>()) }
    var fingerprint by remember { mutableStateOf("") }
    var verified by remember { mutableStateOf(false) }
    var transferStatus by remember { mutableStateOf("") }
    var activeTransfer by remember { mutableStateOf<String?>(null) }
    var incomingCall by remember { mutableStateOf<Boolean?>(null) }
    var localVideo by remember { mutableStateOf<VideoTrack?>(null) }
    var remoteVideo by remember { mutableStateOf<VideoTrack?>(null) }
    var micEnabled by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(true) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingCallAction by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) } // accept, video
    var showLeave by remember { mutableStateOf(false) }
    val latestLines by rememberUpdatedState(lines)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        if (uri != null && bytes != null) try { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } } catch (_: Exception) { status = "Could not save the received file" }
        bytes?.fill(0); pendingExport = null
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val active = peer
        if (uri != null && active != null) {
            scope.launch {
                try {
                    val metadata = queryDocument(context, uri)
                    require(metadata.size in 0..PeerTransport.MAX_FILE_BYTES) { "Files are limited to 10 MiB" }
                    val transferId = UUID.randomUUID().toString(); activeTransfer = transferId
                    transferStatus = "Preparing direct transfer…"
                    val input = context.contentResolver.openInputStream(uri) ?: error("File could not be opened")
                    withContext(Dispatchers.IO) {
                        input.use { stream -> active.sendFile(metadata.name, metadata.mediaType, metadata.size, stream, transferId) { percent -> transferStatus = "Sending directly · $percent%" } }
                    }
                    transferStatus = "File delivered directly to peer"
                } catch (e: Exception) { transferStatus = e.message?.take(100) ?: "Direct file transfer failed; nothing was uploaded or queued" }
                finally { activeTransfer = null }
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = pendingCallAction; pendingCallAction = null
        if (action != null && grants.values.all { it }) {
            if (action.first) peer?.acceptCall(action.second) else peer?.requestCall(action.second)
        } else callStatus = "Microphone/camera permission was not granted; the call was not started."
    }

    DisposableEffect(session) {
        val current = session
        if (current == null) {
            peer = null
            onDispose { }
        } else {
            val connection = PeerTransport(
                context = context,
                apiBase = api,
                session = current,
                client = client,
                onStatus = { status = it },
                onFingerprint = { fingerprint = it; verified = false },
                onVerified = { verified = it },
                onIncomingText = { sender, text -> lines = lines + Line(sender, text, false) },
                onLocalText = { id, text -> lines = lines + Line(current.participant, text, true, id, "Sending…") },
                onDelivered = { id -> lines = lines.map { if (it.id == id) it.copy(status = "Delivered directly") else it } },
                onDeliveryFailed = { id -> lines = lines.map { if (it.id == id) it.copy(status = "Not delivered · not queued") else it } },
                onPurge = {
                    sender -> peer?.clearSender(sender)
                    lines = purgeMessagesFrom(latestLines, sender)
                    pendingExport?.fill(0); pendingExport = null
                },
                onFileProgress = { id, percent, _ -> transferStatus = "Direct transfer · $percent%"; if (activeTransfer == null) activeTransfer = id },
                onReceivedFile = { file -> lines = lines + Line(file.senderId, "File received: ${file.name}", false, file.id, "", file.id, file.name, file.mediaType, file.bytes) },
                onIncomingCall = { video -> incomingCall = video },
                onCallState = { callStatus = it },
                onLocalVideo = { localVideo = it },
                onRemoteVideo = { remoteVideo = it },
            )
            peer = connection
            connection.connect()
            onDispose { connection.leave(); if (peer === connection) peer = null }
        }
    }

    DisposableEffect(lifecycleOwner, peer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) peer?.endCallForLifecycle()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestCallPermissions(accept: Boolean, video: Boolean) {
        val permissions = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            if (accept) peer?.acceptCall(video) else peer?.requestCall(video)
        } else {
            pendingCallAction = accept to video
            permissionLauncher.launch(permissions)
        }
    }

    fun leave() {
        val current = session ?: return
        scope.launch {
            status = "Removing this participant…"
            try {
                withContext(Dispatchers.IO) { apiPost(client, "$api/v1/rooms/${current.room}/exit", JSONObject(), current.credential) }
                lines.forEach { it.bytes?.fill(0) }; lines = emptyList()
                pendingExport?.fill(0); pendingExport = null
                fingerprint = ""; verified = false; invite = ""; callStatus = ""; transferStatus = ""
                session = null
                status = "Session exited. Messages and keys were cleared from this app."
            } catch (_: Exception) { status = "Exit could not reach the room service. Stay online and retry; the server has no message history." }
        }
    }

    if (showLeave) AlertDialog(
        onDismissRequest = { showLeave = false }, title = { Text("Leave and delete your messages?") },
        text = { Text("Your temporary participant session and active transfers will end. The other participant can continue. Exported files, screenshots, or copied media cannot be remotely erased.") },
        confirmButton = { TextButton(onClick = { showLeave = false; leave() }) { Text("Leave and delete") } },
        dismissButton = { TextButton(onClick = { showLeave = false }) { Text("Cancel") } },
    )

    incomingCall?.let { wantsVideo ->
        AlertDialog(
            onDismissRequest = { incomingCall = null; peer?.rejectCall() },
            title = { Text(if (wantsVideo) "Incoming video call" else "Incoming voice call") },
            text = { Text("Accepting requires microphone permission${if (wantsVideo) " and optionally camera permission" else ""}.") },
            confirmButton = { TextButton(onClick = { incomingCall = null; requestCallPermissions(accept = true, video = wantsVideo) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { incomingCall = null; peer?.rejectCall() }) { Text("Decline") } },
        )
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ephemeral Chat", style = MaterialTheme.typography.headlineMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (session == null) {
                Text("Anonymous two-person chat. Messages and files go directly between peers when a path is available; there is no offline queue.")
                if (!endpointConfigured) Text("Room creation and joining are disabled until a real HTTPS Worker endpoint is configured. No messages or media can be sent to a placeholder host.", color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { apiPost(client, "$api/v1/rooms", JSONObject()) }
                            val created = Session(result.getString("roomId"), result.getString("participantId"), result.getString("credential"), result.getString("invitation"), true)
                            invite = created.invitation.orEmpty(); session = created
                            status = "Room created; share the one-use invitation privately."
                        } catch (_: Exception) { status = "Could not create room; check API configuration and the free metadata service." }
                    }
                }, enabled = endpointConfigured) { Text("Create room") }
                OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Invitation link or room:token") }, singleLine = true)
                Button(onClick = {
                    scope.launch {
                        try {
                            val (room, token) = parseInvite(code)
                            val result = withContext(Dispatchers.IO) { apiPost(client, "$api/v1/rooms/$room/join", JSONObject().put("invitation", token)) }
                            session = Session(room, result.getString("participantId"), result.getString("credential"), null, false)
                            status = "Joined; connecting directly to the other participant."
                        } catch (_: Exception) { status = "Could not join. Check the invitation and network." }
                    }
                }, enabled = code.isNotBlank() && endpointConfigured) { Text("Join room") }
            } else {
                if (invite.isNotBlank()) {
                    SelectionContainer { Text("Invitation: $invite", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(invite)); status = "Invitation copied" }) { Text("Copy invitation") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Peer key fingerprint: ${fingerprint.ifBlank { "waiting for peer" }}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Button(onClick = { peer?.confirmFingerprint() }, enabled = fingerprint.isNotBlank() && !verified) { Text("I compared it") }
                }
                Text(if (verified) "Both peers confirmed the shared fingerprint." else "Compare this full fingerprint with the other person over a separate trusted channel before enabling messages or calls.", style = MaterialTheme.typography.bodySmall)
                if (status.contains("unavailable", true) || status.contains("interrupted", true) || status.contains("failed", true) || status.contains("disconnected", true)) {
                    TextButton(onClick = { peer?.reconnect() }) { Text("Reconnect signaling") }
                }
                if (callStatus.isNotBlank()) Text(callStatus)
                if (localVideo != null || remoteVideo != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (remoteVideo != null) PeerVideoPreview(peer, remoteVideo, local = false, Modifier.weight(1f).height(150.dp))
                        if (localVideo != null) PeerVideoPreview(peer, localVideo, local = true, Modifier.weight(1f).height(150.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { requestCallPermissions(accept = false, video = false) }, enabled = verified) { Text("Voice call") }
                    TextButton(onClick = { requestCallPermissions(accept = false, video = true) }, enabled = verified) { Text("Video call") }
                    if (callStatus.isNotBlank()) TextButton(onClick = { micEnabled = !micEnabled; peer?.setMicrophoneEnabled(micEnabled) }) { Text(if (micEnabled) "Mute" else "Unmute") }
                    if (localVideo != null) {
                        TextButton(onClick = { cameraEnabled = !cameraEnabled; peer?.setCameraEnabled(cameraEnabled) }) { Text(if (cameraEnabled) "Camera off" else "Camera on") }
                        TextButton(onClick = { peer?.switchCamera() }) { Text("Switch camera") }
                    }
                    if (callStatus.isNotBlank()) TextButton(onClick = { peer?.hangUp() }) { Text("Hang up") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(lines, key = { it.id }) { line ->
                        Surface(color = if (line.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(line.text)
                                if (line.status.isNotBlank()) Text(line.status, style = MaterialTheme.typography.labelSmall)
                                if (line.bytes != null) TextButton(onClick = { pendingExport = line.bytes.copyOf(); exportLauncher.launch(line.fileName ?: "received-file") }) { Text("Save received file…") }
                            }
                        }
                    }
                }
                if (transferStatus.isNotBlank()) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(transferStatus, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    activeTransfer?.let { id -> TextButton(onClick = { peer?.cancelTransfer(id); transferStatus = "Transfer cancelled"; activeTransfer = null }) { Text("Cancel") } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), label = { Text("Direct encrypted message") }, enabled = verified)
                    Button(onClick = { peer?.sendText(draft.trim())?.let { draft = "" } }, enabled = draft.isNotBlank() && verified) { Text("Send") }
                }
                TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = verified) { Text("Send file directly (max 10 MiB)") }
                Text("Files are chunked, encrypted on this device, and never uploaded. Offline peers do not receive queued files.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showLeave = true }) { Text("Leave chat and delete my session") }
            }
        }
    }
}

@Composable
private fun PeerVideoPreview(transport: PeerTransport?, track: VideoTrack?, local: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember(transport) {
        SurfaceViewRenderer(context).also { view ->
            val eglContext = transport?.eglContext
            if (eglContext != null) view.init(eglContext, null)
        }
    }
    DisposableEffect(renderer, transport, local, track) {
        if (local) transport?.attachLocalRenderer(renderer) else transport?.attachRemoteRenderer(renderer)
        onDispose {
            if (local) transport?.detachLocalRenderer(renderer) else transport?.detachRemoteRenderer(renderer)
            renderer.release()
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

private data class DocumentMeta(val name: String, val mediaType: String, val size: Long)
private fun queryDocument(context: android.content.Context, uri: android.net.Uri): DocumentMeta {
    var name = "attachment"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!cursor.isNull(it)) size = cursor.getLong(it) }
        }
    }
    name = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifBlank { "attachment" }
    val mediaType = context.contentResolver.getType(uri) ?: "application/octet-stream"
    require(size >= 0) { "This file provider did not report a size; choose a regular file to keep memory bounded" }
    return DocumentMeta(name, mediaType, size)
}

private fun parseInvite(input: String): Pair<String, String> {
    val value = input.trim()
    if (value.contains(":") && !value.startsWith("http")) {
        val split = value.split(":", limit = 2); require(split.size == 2 && split.all { it.isNotBlank() }); return split[0] to split[1]
    }
    val uri = URI(value); val parts = uri.path.trim('/').split('/')
    require(parts.size >= 2 && parts[0] == "join")
    val token = uri.rawFragment ?: error("Invitation token missing")
    return parts[1] to token
}

private fun apiPost(client: OkHttpClient, url: String, body: JSONObject, token: String? = null): JSONObject {
    val builder = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
    if (token != null) builder.header("Authorization", "Bearer $token")
    client.newCall(builder.build()).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${text.take(160)}")
        return JSONObject(text)
    }
}

internal object Crypto {
    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    fun shared(pair: KeyPair, peerEncoded: ByteArray, room: String): ByteArray {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerEncoded))
        val agreement = KeyAgreement.getInstance("ECDH"); agreement.init(pair.private); agreement.doPhase(peer, true)
        val raw = agreement.generateSecret(); val prk = hmac(room.toByteArray(), raw)
        return hmac(prk, "ephemeral-chat-v1\u0001".toByteArray()).copyOf(32).also { raw.fill(0); prk.fill(0) }
    }
    fun fingerprint(key: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(key).take(12).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
    fun authenticationTag(key: ByteArray, room: String): ByteArray = hmac(key, "verify-peer:$room".toByteArray())
    fun encrypt(key: ByteArray, room: String, text: String): String = Base64.getEncoder().encodeToString(encryptBytes(key, room, text.toByteArray(Charsets.UTF_8)))
    fun encryptBytes(key: ByteArray, room: String, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); cipher.updateAAD(room.toByteArray())
        return iv + cipher.doFinal(plain)
    }
    fun decrypt(key: ByteArray, room: String, value: String): String = decryptBytes(key, room, Base64.getDecoder().decode(value)).toString(Charsets.UTF_8)
    fun decryptBytes(key: ByteArray, room: String, data: ByteArray): ByteArray {
        require(data.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data.copyOfRange(0, 12))); cipher.updateAAD(room.toByteArray())
        return cipher.doFinal(data.copyOfRange(12, data.size))
    }
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
}
