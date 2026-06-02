package com.example.audioanalysis

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AudioStreamer(private val webSocketUrl: String) {
    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isStreaming = false
    private var isConnected = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    // Use a larger buffer (e.g., 100ms of audio = 3200 bytes) to avoid flooding the API
    private val bufferSize = maxOf(AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat), 3200)

    interface StreamListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)
        fun onMessage(text: String)
    }

    var listener: StreamListener? = null

    @SuppressLint("MissingPermission")
    fun startStreaming() {
        if (isStreaming) return

        val request = Request.Builder().url(webSocketUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.d("AudioStreamer", "WebSocket Connected. Waiting for server signal...")
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val jsonResponse = JSONObject(text)
                    val event = jsonResponse.optString("event")
                    
                    if (event == "CONNECTION_ESTABLISHED") {
                        val status = jsonResponse.optString("status")
                        val serverMessage = jsonResponse.optString("message")
                        System.out.println("AWS SERVER SAYS: $serverMessage (Status: $status)")
                        
                        // Send a single Hello message once ready
                        if (status == "READY") {
                            sendHello()
                        }
                    } else if (event == "SERVER_GREETING") {
                        val serverMessage = jsonResponse.optString("message")
                        System.out.println("AWS SERVER SAYS: $serverMessage")
                    }
                } catch (e: Exception) {
                    Log.e("AudioStreamer", "Error parsing message: ${e.message}")
                }
                Log.d("AudioStreamer", "Message: $text")
                listener?.onMessage(text)
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
            put("action", "sendAudioChunk") // Using your current route name
            put("data", "SGVsbG8=") // Base64 for "Hello"
            put("message", "Hello from Android!")
        }
        webSocket?.send(helloPayload.toString())
        Log.d("AudioStreamer", "Sent Hello message")
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
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
                    val data = if (read < buffer.size) buffer.copyOfRange(0, read) else buffer
                    val base64Data = Base64.encodeToString(data, Base64.NO_WRAP)
                    
                    val audioPayload = JSONObject().apply {
                        put("action", "sendAudioChunk") // Updated to match AWS Route name
                        put("data", base64Data)
                    }
                    val payloadString = audioPayload.toString()
                    Log.d("AudioStreamer", "Streaming ${data.size} bytes: $payloadString")
                    webSocket?.send(payloadString)
                }
            }
        }.start()
    }

    fun stopStreaming() {
        isStreaming = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        webSocket?.close(1000, "User stopped streaming")
        webSocket = null
        listener?.onDisconnected()
    }
}
