package com.example.audioanalysis

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

val DEFAULT_CONFIG = "MSI_AUDIO_PRI=true;cal_devid=148;cal_apptype=69936;cal_caltype=0;cal_samplerate=16000;cal_topoid=0x00000005;cal_moduleid=0x11111300;cal_paramid=0x11111301;cal_persist=0;cal_data"

private fun b64encode(params: IntArray): String? {
    if (params.isEmpty()) {
        return ""
    }
    val byteParams = ByteBuffer.allocateDirect(4 * params.size)
    byteParams.order(ByteOrder.LITTLE_ENDIAN)
    for (i in params.indices) {
        byteParams.putInt(params[i])
    }
    val bparams = ByteArray(4 * params.size)
    byteParams.rewind()
    byteParams.get(bparams)
    return Base64.encodeToString(bparams, Base64.NO_WRAP)
}

class AudioStreamer(private val context: Context, private val webSocketUrl: String) {
    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isStreaming = false
    private var isConnected = false
    private var soundIdValue = 170

    // Local file storage for the session
    private var sessionFile: File? = null
    private var fileOutputStream: FileOutputStream? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    // Use a larger buffer (e.g., 200ms of audio = 6400 bytes) to avoid flooding the API
    private val bufferSize = maxOf(AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat), 6400)

    interface StreamListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)
        fun onMessage(text: String)
    }

    var listener: StreamListener? = null

    private fun sendToWebSocket(text: String) {
        //Log.d("AudioStreamer", "OUTGOING MESSAGE: $text")
        webSocket?.send(text)
    }

    private fun initAudioTrack() {
        val outBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, audioFormat)
        var lastSoundIdValue = 170
        //audioTrack?.start()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        fun updateAudioParameters(value: Int) {
            val base = b64encode(intArrayOf(value))
            val paramString = "$DEFAULT_CONFIG=$base"
            Log.d("AudioStreamer", "Setting audio parameters: $paramString")
            try {
                audioManager.setParameters(paramString)
            } catch (e: Exception) {
                Log.e("AudioStreamer", "Error setting parameters: ${e.message}")
            }
        }

        updateAudioParameters(soundIdValue)
        lastSoundIdValue = soundIdValue
        Log.d("AudioStreamer", "number ${soundIdValue}.")
//        if (lastSoundIdValue != soundIdValue) {
//            updateAudioParameters(soundIdValue)
//            lastSoundIdValue = soundIdValue
//        }
//
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(outBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        //audioTrack?.play()

        Thread {
            while (isConnected) {
                if (lastSoundIdValue != soundIdValue) {
                    updateAudioParameters(soundIdValue)
                    lastSoundIdValue = soundIdValue
                    Log.d("AudioStreamer", "inside thread")
                }
                Thread.sleep(100)
            }
            //audioTrack?.play()
        }.start()
        audioTrack?.play()
    }

    private fun writeToSpeaker(value: Int) {
        soundIdValue = value
        //if (audioTrack == null) {
            initAudioTrack()
        //}
        // Write a short burst of the value to the speaker (Right Channel)
        val burstDurationMs = 100
        val burstSizeSamples = (sampleRate * burstDurationMs) / 1000
        // Interleaved stereo: [Left, Right, Left, Right, ...]
        val buffer = ShortArray(burstSizeSamples * 2) 
        for (i in 0 until burstSizeSamples) {
            // Fill both channels to avoid issues with mono/stereo mismatches
            buffer[i * 2] = soundIdValue.toShort()
            buffer[i * 2 + 1] = soundIdValue.toShort()
        }
        audioTrack?.write(buffer, 0, buffer.size)
    }

    @SuppressLint("MissingPermission")
    fun startStreaming() {
        if (isStreaming) return

        val request = Request.Builder().url(webSocketUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                val connectionHeaders = response.headers.toString()
                Log.d("AudioStreamer", "WebSocket connected. Headers: $connectionHeaders")
                Log.d("AudioStreamer", "Triggering handshake...")

                // Yell up to AWS so it knows it is safe to reply
                val initPayload = JSONObject().apply {
                    put("action", "initializeSession")
                }
                sendToWebSocket(initPayload.toString())

                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("AudioStreamer", "RECEIVING TEXT FRAME: $text")
                try {
                    val jsonResponse = JSONObject(text)
                    val event = jsonResponse.optString("event")

                    if (event == "CONNECTION_ESTABLISHED") {
                        val status = jsonResponse.optString("status")
                        val serverMessage = jsonResponse.optString("message")
                        Log.d("AudioStreamer", "AWS SERVER SAYS: $serverMessage (Status: $status)")

                        if (status == "READY") {
                            //sendHello()
                            startRecording()
                        }
                    } else if (event == "DANGER_DETECTED") {
                        Log.d("AudioStreamer", "DANGER DETECTED! Writing 170 to speaker.")
                        writeToSpeaker(170)
                        soundIdValue = 170
                    } else if (event == "SAFE") {
                        Log.d("AudioStreamer", "SAFE! Writing 204 to speaker.")
                        writeToSpeaker(204)
                        soundIdValue = 204
                    } else if (jsonResponse.has("message") && jsonResponse.getString("message") == "Forbidden") {
                        Log.e("AudioStreamer", "SERVER REJECTED MESSAGE (FORBIDDEN). Full Response: $text")
                    }
                } catch (e: Exception) {
                    Log.e("AudioStreamer", "Error parsing message: ${e.message}")
                }
                listener?.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d("AudioStreamer", "RECEIVING BINARY FRAME: ${bytes.hex()}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AudioStreamer", "Closing: $reason")
                isConnected = false
                stopStreaming()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("AudioStreamer", "Error: ${t.message}")
                isConnected = false
                listener?.onError(t.message ?: "Unknown error")
                stopStreaming()
            }
        })
    }

    fun sendHello() {
        val helloPayload = JSONObject().apply {
            // --- CHANGE THIS FROM sendAudioChunk TO initializeSession ---
            put("action", "initializeSession")
            put("message", "Hello from Android!")
        }
        sendToWebSocket(helloPayload.toString())
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isStreaming) return

        // Initialize local file for this session
        try {
            sessionFile = File.createTempFile("audio_session_", ".pcm", context.cacheDir)
            sessionFile?.deleteOnExit() // Backup cleanup for app exit
            fileOutputStream = FileOutputStream(sessionFile)
            Log.d("AudioStreamer", "Recording session data to: ${sessionFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("AudioStreamer", "Could not create session file: ${e.message}")
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            listener?.onError("AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isStreaming = true

        Thread {
            val buffer = ByteArray(bufferSize)
            while (isStreaming) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // Apply gain of 10x
                    for (i in 0 until read step 2) {
                        if (i + 1 < read) {
                            // Convert little-endian bytes to short
                            var sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                            
                            // Apply gain and clip to valid short range
                            val scaledSample = sample.toInt() * 31.6228
                            sample = when {
                                scaledSample > Short.MAX_VALUE -> Short.MAX_VALUE
                                scaledSample < Short.MIN_VALUE -> Short.MIN_VALUE
                                else -> scaledSample.toInt().toShort()
                            }
                            
                            // Convert back to little-endian bytes
                            buffer[i] = (sample.toInt() and 0xFF).toByte()
                            buffer[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                        }
                    }

                    // Write to local file
                    try {
                        fileOutputStream?.write(buffer, 0, read)
                    } catch (e: Exception) {
                        Log.e("AudioStreamer", "File write error: ${e.message}")
                    }

                    val data = if (read < buffer.size) buffer.copyOfRange(0, read) else buffer
                    val base64Data = Base64.encodeToString(data, Base64.NO_WRAP)

                    val audioPayload = JSONObject().apply {
                        put("action", "sendAudioChunk") // Updated to match AWS Route name
                        put("audio_base64", base64Data)
                    }
                    sendToWebSocket(audioPayload.toString())
                }
            }
        }.start()
    }

    fun stopStreaming() {
        isStreaming = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Close local session file (kept for debugging/analysis)
        try {
            fileOutputStream?.close()
            fileOutputStream = null
            Log.d("AudioStreamer", "Session file saved at: ${sessionFile?.absolutePath}")
            sessionFile = null
        } catch (e: Exception) {
            Log.e("AudioStreamer", "Error during file cleanup: ${e.message}")
        }

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        webSocket?.close(1000, "User stopped streaming")
        webSocket = null
        listener?.onDisconnected()
    }
}
