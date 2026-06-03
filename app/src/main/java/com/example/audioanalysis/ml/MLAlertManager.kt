package com.example.audioanalysis.ml

import com.example.audioanalysis.MqttClientHelper
import org.json.JSONObject
import android.util.Log

/**
 * Manages ML-detected alerts and publishes them via MQTT
 * Uses separate topics from the main cloud pipeline to avoid conflicts
 */
class MLAlertManager(private val mqttHelper: MqttClientHelper) {

    companion object {
        private const val TAG = "MLAlertManager"

        // Separate MQTT topics for ML alerts (don't interfere with cloud pipeline)
        const val ALERT_TOPIC = "audio/ml/alerts"
        const val METADATA_TOPIC = "audio/ml/metadata"
    }

    /**
     * Publish an immediate gunshot alert
     * Uses QoS 2 (exactly once) for critical alerts
     */
    fun publishGunshot(event: ClassifiedEvent) {
        try {
            val payload = JSONObject().apply {
                put("alert_type", "GUNSHOT_DETECTED")
                put("confidence", event.event.confidence)
                put("timestamp", event.event.timestamp)
                put("source", "edge_yamnet")
                put("class_name", event.event.className)
            }

            mqttHelper.publish(ALERT_TOPIC, payload.toString(), qos = 2)
            Log.e(TAG, "🔴 GUNSHOT ALERT published: ${event.event.confidence}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish gunshot alert: ${e.message}", e)
        }
    }

    /**
     * Publish distress vocal metadata
     * Uses QoS 1 (at least once) for metadata
     */
    fun publishDistressVocal(event: ClassifiedEvent) {
        try {
            val payload = JSONObject().apply {
                put("event_type", event.type.name)
                put("class_name", event.event.className)
                put("confidence", event.event.confidence)
                put("timestamp", event.event.timestamp)
            }

            mqttHelper.publish(METADATA_TOPIC, payload.toString(), qos = 1)
            Log.w(TAG, "⚠️ Distress vocal published: ${event.event.className} (${event.event.confidence})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish distress vocal: ${e.message}", e)
        }
    }

    /**
     * Publish general classification results
     * Can be used for debugging or analytics
     */
    fun publishClassification(events: List<ClassifiedEvent>) {
        if (events.isEmpty()) return

        try {
            val payload = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("event_count", events.size)
                val eventsArray = org.json.JSONArray()
                events.forEach { event ->
                    eventsArray.put(JSONObject().apply {
                        put("class", event.event.className)
                        put("confidence", event.event.confidence)
                        put("type", event.type.name)
                    })
                }
                put("events", eventsArray)
            }

            mqttHelper.publish("audio/ml/classifications", payload.toString(), qos = 0)
            Log.d(TAG, "Classification published: ${events.size} events")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish classification: ${e.message}", e)
        }
    }
}
