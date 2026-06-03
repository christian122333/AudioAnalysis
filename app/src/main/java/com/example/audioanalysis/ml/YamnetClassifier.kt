package com.example.audioanalysis.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * YAMNet audio classifier for detecting acoustic events
 * YAMNet is a pre-trained audio event classifier that can identify 521 different audio classes
 */
class YamnetClassifier(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var classLabels: Map<Int, String> = emptyMap()

    // YAMNet specific event indices (these need to be mapped from the actual CSV)
    private val gunshotClasses = setOf("Gunshot, gunfire", "Machine gun", "Artillery fire")
    private val screamingClasses = setOf("Screaming", "Wail, moan")
    private val cryingClasses = setOf("Crying, sobbing", "Baby cry, infant cry", "Whimper")
    private val yellingClasses = setOf("Yell", "Shout", "Battle cry")

    companion object {
        private const val TAG = "YamnetClassifier"
        const val SAMPLE_RATE = 16000
        const val WINDOW_SIZE_SAMPLES = 15600 // 0.975 seconds at 16kHz
        const val INPUT_SIZE = WINDOW_SIZE_SAMPLES
        const val OUTPUT_SIZE = 521 // YAMNet has 521 output classes

        private const val MODEL_FILE = "yamnet.tflite"
        private const val LABELS_FILE = "yamnet_class_map.csv"

        // Confidence thresholds
        private const val GUNSHOT_THRESHOLD = 0.5f
        private const val DISTRESS_THRESHOLD = 0.3f
        private const val TOP_K = 5 // Return top 5 predictions
    }

    /**
     * Initialize the TFLite interpreter and load class labels
     */
    fun initialize(): Boolean {
        return try {
            // Load the TFLite model
            val modelBuffer = loadModelFile()
            val options = Interpreter.Options().apply {
                setNumThreads(4) // Use 4 threads for faster inference
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "YAMNet model loaded successfully")

            // Load class labels
            classLabels = loadClassLabels()
            Log.d(TAG, "Loaded ${classLabels.size} class labels")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YAMNet: ${e.message}", e)
            false
        }
    }

    /**
     * Classify audio samples and return detected events
     */
    fun classifyAudio(audioSamples: ShortArray): List<ClassifiedEvent> {
        if (interpreter == null) {
            Log.e(TAG, "Interpreter not initialized")
            return emptyList()
        }

        if (audioSamples.size != WINDOW_SIZE_SAMPLES) {
            Log.w(TAG, "Audio buffer size mismatch: expected $WINDOW_SIZE_SAMPLES, got ${audioSamples.size}")
            return emptyList()
        }

        return try {
            // Preprocess audio
            val inputBuffer = preprocessAudio(audioSamples)

            // Prepare output buffer
            val outputBuffer = Array(1) { FloatArray(OUTPUT_SIZE) }

            // Run inference
            interpreter?.run(inputBuffer, outputBuffer)

            // Get top predictions
            val predictions = outputBuffer[0]
            val topPredictions = getTopPredictions(predictions, TOP_K)

            // Convert to classified events
            topPredictions.mapNotNull { (classIndex, confidence) ->
                val className = classLabels[classIndex] ?: "Unknown"
                categorizeEvent(className, confidence)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classification error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Clean up resources
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "YAMNet classifier closed")
    }

    /**
     * Load the TFLite model from assets
     */
    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Load class labels from CSV file
     */
    private fun loadClassLabels(): Map<Int, String> {
        val labels = mutableMapOf<Int, String>()

        try {
            context.assets.open(LABELS_FILE).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    // Skip header line
                    reader.readLine()

                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let {
                            val parts = it.split(",")
                            if (parts.size >= 3) {
                                val index = parts[0].toIntOrNull()
                                val displayName = parts[2].trim('"')
                                if (index != null) {
                                    labels[index] = displayName
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading class labels: ${e.message}", e)
        }

        return labels
    }

    /**
     * Preprocess audio samples for YAMNet input
     * Convert PCM Int16 [-32768, 32767] to Float32 [-1.0, 1.0]
     */
    private fun preprocessAudio(samples: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE) // 4 bytes per float
        buffer.order(ByteOrder.nativeOrder())

        for (sample in samples) {
            // Normalize: convert Int16 to Float32 in range [-1.0, 1.0]
            val normalized = sample.toFloat() / 32768.0f
            buffer.putFloat(normalized)
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Get top K predictions from output
     */
    private fun getTopPredictions(predictions: FloatArray, k: Int): List<Pair<Int, Float>> {
        return predictions
            .mapIndexed { index, confidence -> index to confidence }
            .sortedByDescending { it.second }
            .take(k)
    }

    /**
     * Categorize an event based on its class name
     */
    private fun categorizeEvent(className: String, confidence: Float): ClassifiedEvent? {
        val type = when {
            gunshotClasses.any { className.contains(it, ignoreCase = true) } -> EventType.GUNSHOT
            screamingClasses.any { className.contains(it, ignoreCase = true) } -> EventType.SCREAMING
            cryingClasses.any { className.contains(it, ignoreCase = true) } -> EventType.CRYING
            yellingClasses.any { className.contains(it, ignoreCase = true) } -> EventType.YELLING
            else -> EventType.OTHER
        }

        // Only return events we care about
        if (type == EventType.OTHER) {
            return null
        }

        // Check if it meets threshold
        val meetsThreshold = when (type) {
            EventType.GUNSHOT -> confidence >= GUNSHOT_THRESHOLD
            EventType.SCREAMING, EventType.CRYING, EventType.YELLING -> confidence >= DISTRESS_THRESHOLD
            else -> false
        }

        if (!meetsThreshold) {
            return null
        }

        return ClassifiedEvent(
            event = AudioEvent(className, confidence),
            type = type,
            isEmergency = (type == EventType.GUNSHOT)
        )
    }
}
