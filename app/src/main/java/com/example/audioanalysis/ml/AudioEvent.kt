package com.example.audioanalysis.ml

/**
 * Represents a single audio event detected by the YAMNet classifier
 */
data class AudioEvent(
    val className: String,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Types of audio events we care about for emergency detection
 */
enum class EventType {
    GUNSHOT,
    SCREAMING,
    CRYING,
    YELLING,
    OTHER
}

/**
 * A classified audio event with categorization
 */
data class ClassifiedEvent(
    val event: AudioEvent,
    val type: EventType,
    val isEmergency: Boolean = false
)
