package com.example.audioanalysis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.example.audioanalysis.ml.AudioProcessor
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class AudioStreamer(
    private val context: Context,
    private val webSocketUrl: String,
    private var mlProcessor: AudioProcessor? = null
) {
    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isStreaming = false
    private var isConnected = false

    // Local file storage for the session
    private var sessionFile: File? = null
    private var fileOutputStream: FileOutputStream? = null

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

    private fun sendToWebSocket(text: String) {
        //Log.d("AudioStreamer", "OUTGOING MESSAGE: $text")
        webSocket?.send(text)
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
                startRecording()
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
                    } else if (jsonResponse.has("message") && jsonResponse.getString("message") == "Forbidden") {
                        Log.e("AudioStreamer", "SERVER REJECTED MESSAGE (FORBIDDEN). Full Response: $text")
                    }
                } catch (e: Exception) {
                    Log.e("AudioStreamer", "Error parsing message: ${e.message}")
                }
                listener?.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
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
                            val scaledSample = sample.toInt() * 10
                            sample = when {
                                scaledSample > Short.MAX_VALUE -> Short.MAX_VALUE
                                scaledSample < Short.MIN_VALUE -> Short.MIN_VALUE
                                else -> scaledSample.toShort()
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

                        // Add acoustic events if ML detected any
                        mlProcessor?.getPendingEvents()?.let { events ->
                            if (events.isNotEmpty()) {
                                val eventsArray = org.json.JSONArray()
                                events.forEach { classified ->
                                    eventsArray.put(JSONObject().apply {
                                        put("event", classified.type.name.lowercase())
                                        put("confidence", classified.event.confidence)
                                        put("timestamp", classified.event.timestamp)
                                    })
                                }
                                put("acoustic_events", eventsArray)
                                Log.d("AudioStreamer", "Added ${events.size} acoustic events to payload")
                            }
                        }
                    }
                    sendToWebSocket(audioPayload.toString())

                    // Pass audio to ML processor if present (modular - optional)
                    mlProcessor?.processChunk(data)
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

        webSocket?.close(1000, "User stopped streaming")
        webSocket = null
        listener?.onDisconnected()
    }
}
