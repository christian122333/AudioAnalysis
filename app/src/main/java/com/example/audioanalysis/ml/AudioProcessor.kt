package com.example.audioanalysis.ml

import android.util.Log

/**
 * Audio buffer processor that accumulates audio chunks and triggers ML inference
 * Uses observer pattern to avoid modifying AudioStreamer
 */
class AudioProcessor(private val classifier: YamnetClassifier) {
    private val audioBuffer = mutableListOf<Short>()
    private val pendingAcousticEvents = mutableListOf<ClassifiedEvent>()
    private var callback: AudioProcessorCallback? = null

    companion object {
        private const val TAG = "AudioProcessor"
    }

    /**
     * Callback interface for ML detection events
     */
    interface AudioProcessorCallback {
        fun onGunshot(event: ClassifiedEvent)
        fun onDistressVocal(event: ClassifiedEvent)
        fun onClassification(events: List<ClassifiedEvent>)
    }

    /**
     * Set the callback for receiving ML events
     */
    fun setCallback(callback: AudioProcessorCallback) {
        this.callback = callback
    }

    /**
     * Get pending acoustic events and clear the list
     * Called by AudioStreamer to include events in WebSocket JSON payload
     */
    @Synchronized
    fun getPendingEvents(): List<ClassifiedEvent> {
        val events = pendingAcousticEvents.toList()
        pendingAcousticEvents.clear()
        return events
    }

    /**
     * Process an audio chunk (called from audio recording loop)
     * This method accumulates audio data and triggers classification when buffer is full
     */
    fun processChunk(audioData: ByteArray) {
        try {
            // Convert bytes to shorts (PCM 16-bit little-endian)
            for (i in audioData.indices step 2) {
                if (i + 1 < audioData.size) {
                    // Little-endian: low byte first, then high byte
                    val low = audioData[i].toInt() and 0xFF
                    val high = audioData[i + 1].toInt() shl 8
                    val sample = (high or low).toShort()
                    audioBuffer.add(sample)
                }
            }

            // Process when buffer has enough samples for YAMNet window
            if (audioBuffer.size >= YamnetClassifier.WINDOW_SIZE_SAMPLES) {
                val window = audioBuffer.take(YamnetClassifier.WINDOW_SIZE_SAMPLES).toShortArray()
                audioBuffer.subList(0, YamnetClassifier.WINDOW_SIZE_SAMPLES).clear()

                // Run classification in background to avoid blocking audio thread
                Thread {
                    classifyWindow(window)
                }.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio chunk: ${e.message}", e)
        }
    }

    /**
     * Classify a window of audio and trigger callbacks
     */
    private fun classifyWindow(window: ShortArray) {
        try {
            val events = classifier.classifyAudio(window)

            if (events.isEmpty()) {
                return
            }

            // Notify callback of all classifications
            callback?.onClassification(events)

            // Trigger specific callbacks based on event type
            events.forEach { classified ->
                when (classified.type) {
                    EventType.GUNSHOT -> {
                        if (classified.event.confidence > 0.5f) {
                            Log.e(TAG, "GUNSHOT DETECTED: ${classified.event.className} (${classified.event.confidence})")
                            callback?.onGunshot(classified)
                        }
                    }
                    EventType.SCREAMING, EventType.CRYING, EventType.YELLING -> {
                        if (classified.event.confidence > 0.3f) {
                            Log.w(TAG, "Distress vocal detected: ${classified.event.className} (${classified.event.confidence})")
                            // Store for inclusion in next WebSocket payload
                            synchronized(pendingAcousticEvents) {
                                pendingAcousticEvents.add(classified)
                            }
                            callback?.onDistressVocal(classified)
                        }
                    }
                    else -> {
                        // Ignore other events
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error classifying window: ${e.message}", e)
        }
    }

    /**
     * Clear the audio buffer
     */
    fun clearBuffer() {
        audioBuffer.clear()
    }
}
