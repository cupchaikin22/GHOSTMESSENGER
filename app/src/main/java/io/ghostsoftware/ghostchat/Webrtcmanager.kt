package io.ghostsoftware.ghostchat

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.*

/**
 * WebRTC Manager — полный, без дублирования, с защитой от повторной инициализации
 */
class WebRTCManager(
    private val context: Context,
    private val myUserId: String
) {

    companion object {
        private const val TAG = "WebRTCManager"

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
    }

    // WebRTC Components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    // EglBase контекст — публичный для CallScreen
    var eglBaseContext: EglBase.Context? = null
        private set

    // State flows для UI
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack

    // Call state
    private var currentCallId: String? = null
    private var remoteUserId: String? = null
    private var isInitialized = false

    // Firebase signaling
    private val database = FirebaseDatabase.getInstance()
    private var signalingListener: ValueEventListener? = null

    // ══════════════════════════════════════════════
    // ИНИЦИАЛИЗАЦИЯ
    // ══════════════════════════════════════════════

    /**
     * Безопасная инициализация с защитой от повторного вызова
     */
    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, skipping")
            return
        }

        try {
            Log.d(TAG, "Initializing WebRTC...")

            // Инициализация библиотеки
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("")
                .createInitializationOptions()

            PeerConnectionFactory.initialize(options)
            Log.d(TAG, "✅ Library initialized")

            // Создание EglBase с fallback
            try {
                val eglBase = EglBase.create()
                eglBaseContext = eglBase.eglBaseContext
                Log.d(TAG, "✅ EglBase created")
            } catch (e: Exception) {
                Log.w(TAG, "EglBase creation failed, using null context", e)
                eglBaseContext = null
            }

            // Создание кодеков
            val encoderFactory = if (eglBaseContext != null) {
                DefaultVideoEncoderFactory(eglBaseContext, true, true)
            } else {
                SoftwareVideoEncoderFactory()
            }

            val decoderFactory = if (eglBaseContext != null) {
                DefaultVideoDecoderFactory(eglBaseContext)
            } else {
                SoftwareVideoDecoderFactory()
            }

            // Создание фабрики
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = false
                })
                .createPeerConnectionFactory()

            if (peerConnectionFactory == null) {
                throw IllegalStateException("Failed to create PeerConnectionFactory")
            }

            isInitialized = true
            Log.i(TAG, "✅ WebRTC initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Initialization failed", e)
            _callState.value = CallState.Error("Init failed: ${e.message}")
            isInitialized = false
        }
    }

    /**
     * Автоинициализация — вызывается перед каждым действием
     */
    private fun ensureInitialized(): Boolean {
        if (!isInitialized || peerConnectionFactory == null) {
            Log.w(TAG, "Not initialized, attempting auto-init...")
            try {
                initialize()
            } catch (e: Exception) {
                Log.e(TAG, "Auto-init failed: ${e.message}")
                _callState.value = CallState.Error("Not initialized: ${e.message}")
                return false
            }
        }

        if (!isInitialized || peerConnectionFactory == null) {
            Log.e(TAG, "Still not initialized after auto-init")
            _callState.value = CallState.Error("Not initialized")
            return false
        }

        return true
    }

    // ══════════════════════════════════════════════
    // ЗВОНКИ
    // ══════════════════════════════════════════════

    /**
     * Начать исходящий звонок
     */
    fun startCall(
        recipientId: String,
        isVideoCall: Boolean,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ensureInitialized()) {
            onError("WebRTC not initialized")
            return
        }

        if (_callState.value !is CallState.Idle) {
            onError("Already in call")
            return
        }

        try {
            Log.d(TAG, "Starting call to $recipientId (video: $isVideoCall)")

            remoteUserId = recipientId
            currentCallId = "call_${myUserId}_${recipientId}_${System.currentTimeMillis()}"
            _callState.value = CallState.Calling(recipientId, isVideoCall)

            if (!createPeerConnection()) {
                onError("Failed to create peer connection")
                endCall()
                return
            }

            if (!createLocalMediaTracks(isVideoCall)) {
                onError("Failed to create media tracks")
                endCall()
                return
            }

            createOffer { sdp ->
                sendSignalingMessage(
                    recipientId = recipientId,
                    callId = currentCallId!!,
                    type = "offer",
                    sdp = sdp.description,
                    isVideoCall = isVideoCall
                )
                listenForSignaling(recipientId)
                onSuccess()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call", e)
            onError(e.message ?: "Unknown error")
            endCall()
        }
    }

    /**
     * Ответить на входящий звонок
     */
    fun answerCall(
        callId: String,
        callerId: String,
        offerSdp: String,
        isVideoCall: Boolean,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ensureInitialized()) {
            onError("WebRTC not initialized")
            return
        }

        try {
            Log.d(TAG, "Answering call $callId from $callerId")

            currentCallId = callId
            remoteUserId = callerId
            _callState.value = CallState.Connecting

            if (!createPeerConnection()) {
                onError("Failed to create peer connection")
                endCall()
                return
            }

            if (!createLocalMediaTracks(isVideoCall)) {
                onError("Failed to create media tracks")
                endCall()
                return
            }

            val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set, creating answer...")
                    createAnswer { answerSdp ->
                        sendSignalingMessage(
                            recipientId = callerId,
                            callId = callId,
                            type = "answer",
                            sdp = answerSdp.description
                        )
                        listenForSignaling(callerId)
                        onSuccess()
                    }
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set remote description failed: $error")
                    onError("Set remote failed: $error")
                    endCall()
                }

                override fun onCreateFailure(p0: String?) {}
            }, remoteDesc)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to answer call", e)
            onError(e.message ?: "Unknown error")
            endCall()
        }
    }

    // ══════════════════════════════════════════════
    // PEER CONNECTION
    // ══════════════════════════════════════════════

    /**
     * Создание PeerConnection с полной реализацией Observer
     */
    private fun createPeerConnection(): Boolean {
        try {
            val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                keyType = PeerConnection.KeyType.ECDSA
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(
                config,
                object : PeerConnection.Observer {

                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let { ice ->
                            Log.d(TAG, "ICE candidate: ${ice.sdpMid}")
                            val userId = remoteUserId ?: return
                            val callId = currentCallId ?: return
                            sendIceCandidate(userId, callId, ice)
                        }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                        Log.d(TAG, "ICE candidates removed: ${candidates?.size}")
                    }

                    override fun onAddStream(stream: MediaStream?) {
                        Log.d(TAG, "Remote stream added, video tracks: ${stream?.videoTracks?.size}")
                        stream?.videoTracks?.firstOrNull()?.let { track ->
                            _remoteVideoTrack.value = track
                        }
                    }

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        Log.d(TAG, "Connection state: $newState")
                        when (newState) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                _callState.value = CallState.Connected(remoteUserId ?: "")
                            }
                            PeerConnection.PeerConnectionState.FAILED -> {
                                Log.e(TAG, "Connection FAILED")
                                _callState.value = CallState.Error("Connection failed")
                                endCall()
                            }
                            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                Log.w(TAG, "Connection DISCONNECTED")
                                endCall()
                            }
                            PeerConnection.PeerConnectionState.CLOSED -> {
                                Log.d(TAG, "Connection CLOSED")
                            }
                            else -> {}
                        }
                    }

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                        Log.d(TAG, "ICE connection state: $newState")
                    }

                    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                        Log.d(TAG, "Signaling state: $newState")
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {
                        Log.d(TAG, "ICE receiving: $receiving")
                    }

                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                        Log.d(TAG, "ICE gathering state: $newState")
                    }

                    override fun onRemoveStream(stream: MediaStream?) {
                        Log.d(TAG, "Remote stream removed")
                        _remoteVideoTrack.value = null
                    }

                    override fun onDataChannel(dataChannel: DataChannel?) {
                        Log.d(TAG, "Data channel: ${dataChannel?.label()}")
                    }

                    override fun onRenegotiationNeeded() {
                        Log.d(TAG, "Renegotiation needed")
                    }

                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                        Log.d(TAG, "Track added: ${receiver?.track()?.kind()}")
                        val track = receiver?.track()
                        if (track is VideoTrack) {
                            _remoteVideoTrack.value = track
                        }
                    }
                }
            )

            val success = peerConnection != null
            Log.d(TAG, "PeerConnection created: $success")
            return success

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create peer connection", e)
            return false
        }
    }

    // ══════════════════════════════════════════════
    // МЕДИА ТРЕКИ
    // ══════════════════════════════════════════════

    /**
     * Создание аудио и видео треков
     */
    private fun createLocalMediaTracks(isVideoCall: Boolean): Boolean {
        try {
            // ── Аудио трек ──
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }

            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            audioTrack = peerConnectionFactory?.createAudioTrack("audio_${System.currentTimeMillis()}", audioSource)

            if (audioTrack == null) {
                Log.e(TAG, "Failed to create audio track")
                return false
            }

            audioTrack?.setEnabled(true)
            peerConnection?.addTrack(audioTrack, listOf("stream"))
            Log.d(TAG, "✅ Audio track created and added")

            // ── Видео трек (если нужен) ──
            if (isVideoCall) {
                try {
                    val capturer = createCameraCapturer()
                    if (capturer == null) {
                        Log.w(TAG, "No camera available, continuing audio-only")
                        return true
                    }

                    videoCapturer = capturer
                    videoSource = peerConnectionFactory?.createVideoSource(capturer.isScreencast)

                    if (videoSource == null) {
                        Log.e(TAG, "Failed to create video source")
                        return true
                    }

                    if (eglBaseContext != null) {
                        val surfaceTextureHelper = SurfaceTextureHelper.create(
                            "CaptureThread",
                            eglBaseContext
                        )
                        capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
                        capturer.startCapture(640, 480, 30)
                        Log.d(TAG, "✅ Video capture started (640x480@30)")
                    } else {
                        Log.w(TAG, "No EGL context, skipping video capture")
                        return true
                    }

                    videoTrack = peerConnectionFactory?.createVideoTrack(
                        "video_${System.currentTimeMillis()}",
                        videoSource
                    )

                    if (videoTrack != null) {
                        videoTrack?.setEnabled(true)
                        _localVideoTrack.value = videoTrack
                        peerConnection?.addTrack(videoTrack, listOf("stream"))
                        Log.d(TAG, "✅ Video track created and added")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Video track creation failed, continuing audio-only", e)
                }
            }

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create media tracks", e)
            return false
        }
    }

    /**
     * Создание camera capturer с fallback Camera2 → Camera1
     */
    private fun createCameraCapturer(): CameraVideoCapturer? {
        return try {
            // Camera2 API
            val enumerator = Camera2Enumerator(context)
            val deviceNames = enumerator.deviceNames

            // Фронтальная камера
            for (deviceName in deviceNames) {
                if (enumerator.isFrontFacing(deviceName)) {
                    val capturer = enumerator.createCapturer(deviceName, null)
                    if (capturer != null) {
                        Log.d(TAG, "✅ Front camera (Camera2): $deviceName")
                        return capturer
                    }
                }
            }

            // Задняя камера
            for (deviceName in deviceNames) {
                if (!enumerator.isFrontFacing(deviceName)) {
                    val capturer = enumerator.createCapturer(deviceName, null)
                    if (capturer != null) {
                        Log.d(TAG, "✅ Back camera (Camera2): $deviceName")
                        return capturer
                    }
                }
            }

            // Fallback: Camera1 API
            Log.w(TAG, "Camera2 failed, trying Camera1...")
            val camera1Enumerator = Camera1Enumerator(true)
            for (deviceName in camera1Enumerator.deviceNames) {
                if (camera1Enumerator.isFrontFacing(deviceName)) {
                    val capturer = camera1Enumerator.createCapturer(deviceName, null)
                    if (capturer != null) {
                        Log.d(TAG, "✅ Front camera (Camera1): $deviceName")
                        return capturer
                    }
                }
            }

            Log.e(TAG, "No camera available")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capturer", e)
            null
        }
    }

    // ══════════════════════════════════════════════
    // SDP (OFFER / ANSWER)
    // ══════════════════════════════════════════════

    /**
     * Создание SDP Offer
     */
    private fun createOffer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { offer ->
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "✅ Local description (offer) set")
                            onSuccess(offer)
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local offer failed: $error")
                        }

                        override fun onCreateFailure(p0: String?) {}
                    }, offer)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
            }

            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    /**
     * Создание SDP Answer
     */
    private fun createAnswer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { answer ->
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "✅ Local description (answer) set")
                            onSuccess(answer)
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local answer failed: $error")
                        }

                        override fun onCreateFailure(p0: String?) {}
                    }, answer)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
            }

            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    // ══════════════════════════════════════════════
    // FIREBASE SIGNALING
    // ══════════════════════════════════════════════

    /**
     * Отправка SDP через Firebase
     */
    private fun sendSignalingMessage(
        recipientId: String,
        callId: String,
        type: String,
        sdp: String? = null,
        isVideoCall: Boolean = false
    ) {
        val data = mapOf(
            "from" to myUserId,
            "to" to recipientId,
            "callId" to callId,
            "type" to type,
            "sdp" to (sdp ?: ""),
            "isVideoCall" to isVideoCall,
            "timestamp" to System.currentTimeMillis()
        )

        database.getReference("calls")
            .child(recipientId)
            .child(callId)
            .setValue(data)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Signaling sent: $type → $recipientId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Signaling failed: ${e.message}")
            }
    }

    /**
     * Отправка ICE Candidate через Firebase
     */
    private fun sendIceCandidate(recipientId: String, callId: String, candidate: IceCandidate) {
        val data = mapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "sdp" to candidate.sdp
        )

        database.getReference("calls")
            .child(recipientId)
            .child(callId)
            .child("candidates")
            .push()
            .setValue(data)
    }

    /**
     * Прослушивание ответов и ICE кандидатов
     */
    private fun listenForSignaling(userId: String) {
        // Удаляем старый listener
        signalingListener?.let {
            database.getReference("calls").child(myUserId).removeEventListener(it)
        }

        val ref = database.getReference("calls").child(myUserId)

        signalingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (callSnapshot in snapshot.children) {
                    val type = callSnapshot.child("type").getValue(String::class.java)
                    val from = callSnapshot.child("from").getValue(String::class.java)

                    // Обработка answer
                    if (from == userId && type == "answer") {
                        val sdp = callSnapshot.child("sdp").getValue(String::class.java)
                        if (sdp != null) {
                            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)

                            peerConnection?.setRemoteDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    Log.i(TAG, "✅ Remote description (answer) set")
                                }

                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetFailure(error: String?) {
                                    Log.e(TAG, "Set remote answer failed: $error")
                                }

                                override fun onCreateFailure(p0: String?) {}
                            }, answer)
                        }
                    }

                    // Обработка ICE candidates
                    callSnapshot.child("candidates").children.forEach { candidateSnapshot ->
                        val sdpMid = candidateSnapshot.child("sdpMid").getValue(String::class.java)
                        val sdpMLineIndex = candidateSnapshot.child("sdpMLineIndex").getValue(Int::class.java)
                        val sdp = candidateSnapshot.child("sdp").getValue(String::class.java)

                        if (sdpMid != null && sdpMLineIndex != null && sdp != null) {
                            try {
                                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                                peerConnection?.addIceCandidate(iceCandidate)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to add ICE candidate", e)
                            }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Signaling listener error: ${error.message}")
            }
        }

        ref.addValueEventListener(signalingListener!!)
        Log.d(TAG, "✅ Signaling listener started for $userId")
    }

    // ══════════════════════════════════════════════
    // УПРАВЛЕНИЕ ЗВОНКОМ
    // ══════════════════════════════════════════════

    /**
     * Переключение камеры (фронт/задняя)
     */
    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                Log.d(TAG, "Camera switched, front: $isFrontCamera")
            }

            override fun onCameraSwitchError(error: String?) {
                Log.e(TAG, "Camera switch error: $error")
            }
        })
    }

    /**
     * Включение/выключение микрофона
     */
    fun toggleMicrophone(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
        Log.d(TAG, "Microphone ${if (enabled) "ON" else "OFF"}")
    }

    /**
     * Включение/выключение камеры
     */
    fun toggleCamera(enabled: Boolean) {
        videoTrack?.setEnabled(enabled)
        Log.d(TAG, "Camera ${if (enabled) "ON" else "OFF"}")
    }

    // ══════════════════════════════════════════════
    // ЗАВЕРШЕНИЕ И ОЧИСТКА
    // ══════════════════════════════════════════════

    /**
     * Завершение звонка с очисткой всех ресурсов
     */
    fun endCall() {
        try {
            Log.d(TAG, "Ending call...")

            // 1. Удаляем signaling listener
            signalingListener?.let {
                database.getReference("calls").child(myUserId).removeEventListener(it)
                signalingListener = null
            }

            // 2. Удаляем данные звонка из Firebase
            currentCallId?.let { callId ->
                database.getReference("calls").child(myUserId).child(callId).removeValue()
                remoteUserId?.let { recipientId ->
                    database.getReference("calls").child(recipientId).child(callId).removeValue()
                }
            }

            // 3. Останавливаем видео захват
            try {
                videoCapturer?.stopCapture()
            } catch (e: Exception) {
                Log.w(TAG, "Stop capture error: ${e.message}")
            }
            try {
                videoCapturer?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Dispose capturer error: ${e.message}")
            }
            videoCapturer = null

            // 4. Очищаем треки
            videoTrack?.setEnabled(false)
            videoTrack = null
            audioTrack?.setEnabled(false)
            audioTrack = null

            // 5. Очищаем sources
            try {
                videoSource?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Dispose video source error: ${e.message}")
            }
            videoSource = null

            try {
                audioSource?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Dispose audio source error: ${e.message}")
            }
            audioSource = null

            // 6. Закрываем peer connection
            try {
                peerConnection?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Close peer connection error: ${e.message}")
            }
            try {
                peerConnection?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Dispose peer connection error: ${e.message}")
            }
            peerConnection = null

            // 7. Сбрасываем state
            _remoteVideoTrack.value = null
            _localVideoTrack.value = null
            _callState.value = CallState.Idle
            currentCallId = null
            remoteUserId = null

            Log.i(TAG, "✅ Call ended and cleaned up")

        } catch (e: Exception) {
            Log.e(TAG, "Error ending call", e)
            // Принудительный сброс state
            _callState.value = CallState.Idle
            _remoteVideoTrack.value = null
            _localVideoTrack.value = null
        }
    }

    /**
     * Полное освобождение ресурсов (при выходе из приложения)
     */
    fun dispose() {
        Log.d(TAG, "Disposing WebRTC Manager...")
        endCall()

        try {
            peerConnectionFactory?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Dispose factory error: ${e.message}")
        }
        peerConnectionFactory = null
        eglBaseContext = null
        isInitialized = false

        Log.i(TAG, "✅ WebRTC Manager disposed")
    }
}

/**
 * Состояния звонка
 */
sealed class CallState {
    object Idle : CallState()
    data class Calling(val recipientId: String, val isVideoCall: Boolean) : CallState()
    object Connecting : CallState()
    data class Connected(val userId: String) : CallState()
    data class Error(val message: String) : CallState()
}