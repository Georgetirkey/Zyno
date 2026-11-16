package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.example.permissions.HearthPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class EarphoneState(
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val deviceTypeLabel: String? = null,
    val isMonitoringActive: Boolean = false,
    val monitorGain: Float = 0.85f
)

class VoiceAudioController(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _earphoneState = MutableStateFlow(EarphoneState())
    val earphoneState: StateFlow<EarphoneState> = _earphoneState.asStateFlow()

    private var audioFocusRequest: AudioFocusRequest? = null
    private var monitorJob: Job? = null
    private var preferredSpeakerOn: Boolean = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshConnectedEarphones()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshConnectedEarphones()
        }
    }

    init {
        refreshConnectedEarphones()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
    }

    fun refreshConnectedEarphones() {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headsetDevice = outputDevices.firstOrNull { isEarphoneDeviceType(it.type) }
        val connected = headsetDevice != null
        val typeLabel = headsetDevice?.let { deviceTypeLabel(it.type) }
        val name = headsetDevice?.productName?.toString()?.takeIf { it.isNotBlank() }

        _earphoneState.update { state ->
            state.copy(
                isConnected = connected,
                deviceName = name,
                deviceTypeLabel = typeLabel
            )
        }

        if (!connected && _earphoneState.value.isMonitoringActive) {
            // Stop loopback immediately when earphones are unplugged to prevent speaker feedback
            stopEarphoneMonitoring()
        }

        applyAudioRouting()
    }

    fun setSpeakerphoneEnabled(enabled: Boolean) {
        preferredSpeakerOn = enabled
        applyAudioRouting()
    }

    private fun applyAudioRouting() {
        val earphonesConnected = _earphoneState.value.isConnected
        val routeToSpeaker = !earphonesConnected && preferredSpeakerOn

        runCatching {
            if (earphonesConnected || routeToSpeaker) {
                requestVoiceAudioFocus()
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            } else {
                audioManager.mode = AudioManager.MODE_NORMAL
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (earphonesConnected) {
                    val commDevices = audioManager.availableCommunicationDevices
                    val preferredHeadset = commDevices.firstOrNull { isCommunicationHeadsetType(it.type) }
                    if (preferredHeadset != null) {
                        audioManager.setCommunicationDevice(preferredHeadset)
                    } else {
                        audioManager.clearCommunicationDevice()
                    }
                } else if (routeToSpeaker) {
                    val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speakerDevice != null) {
                        audioManager.setCommunicationDevice(speakerDevice)
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = routeToSpeaker
            }
        }
    }

    fun setMonitorGain(gain: Float) {
        _earphoneState.update { it.copy(monitorGain = gain.coerceIn(0.1f, 1.5f)) }
    }

    @SuppressLint("MissingPermission")
    fun startEarphoneMonitoring(): Boolean {
        if (!HearthPermissions.isMicrophoneGranted(appContext)) return false
        if (!_earphoneState.value.isConnected) return false
        if (monitorJob?.isActive == true) return true

        requestVoiceAudioFocus()
        _earphoneState.update { it.copy(isMonitoringActive = true) }

        monitorJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val sampleRate = chooseSupportedSampleRate()
            val minRecBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)
            val minPlayBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)
            val bufferBytes = maxOf(minRecBytes, minPlayBytes)
            val frameShorts = bufferBytes / 2

            var recorder: AudioRecord? = null
            var player: AudioTrack? = null

            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes * 2
                )

                val trackBuilder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferBytes * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }

                player = trackBuilder.build()

                if (recorder.state != AudioRecord.STATE_INITIALIZED ||
                    player.state != AudioTrack.STATE_INITIALIZED
                ) {
                    _earphoneState.update { it.copy(isMonitoringActive = false) }
                    return@launch
                }

                val buffer = ShortArray(frameShorts)
                recorder.startRecording()
                player.play()

                while (isActive && _earphoneState.value.isConnected) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val gain = _earphoneState.value.monitorGain
                        if (gain != 1.0f) {
                            for (i in 0 until read) {
                                val scaled = (buffer[i] * gain).roundToInt()
                                buffer[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }
                        player.write(buffer, 0, read)
                    }
                }
            } catch (_: Throwable) {
                // Gracefully exit loopback on audio hardware interruption
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                runCatching { player?.stop() }
                runCatching { player?.release() }
                _earphoneState.update { it.copy(isMonitoringActive = false) }
            }
        }
        return true
    }

    fun stopEarphoneMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _earphoneState.update { it.copy(isMonitoringActive = false) }
    }

    fun requestVoiceAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAcceptsDelayedFocusGain(true)
                        .build()
                }
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        }
    }

    fun release() {
        stopEarphoneMonitoring()
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        scope.cancel()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
    }

    private fun chooseSupportedSampleRate(): Int {
        val candidates = intArrayOf(48000, 44100, 16000)
        for (rate in candidates) {
            val size = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (size > 0) return rate
        }
        return 16000
    }

    private fun isEarphoneDeviceType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun isCommunicationHeadsetType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun deviceTypeLabel(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Earphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C Earphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Earphones"
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET
            ) {
                "LE Audio Earphones"
            } else {
                "Earphones"
            }
        }
    }
}
