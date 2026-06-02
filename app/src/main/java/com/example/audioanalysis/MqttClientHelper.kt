package com.example.audioanalysis

import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MqttClientHelper(
    private val serverUri: String,
    private val clientId: String = MqttClient.generateClientId()
) {
    private var mqttClient: MqttClient? = null

    interface MqttListener {
        fun onMessageReceived(topic: String, message: String)
        fun onError(message: String)
        fun onConnected()
    }

    var listener: MqttListener? = null

    fun connect(topic: String) {
        try {
            mqttClient = MqttClient(serverUri, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                // Add SSL factory here if using AWS IoT Core (MQTTS)
            }

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.e("MqttHelper", "Connection lost: ${cause?.message}")
                    listener?.onError("Connection lost: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    Log.d("MqttHelper", "Message from $topic: ${message.toString()}")
                    listener?.onMessageReceived(topic ?: "", message.toString())
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient?.connect(options)
            mqttClient?.subscribe(topic)
            listener?.onConnected()
            Log.d("MqttHelper", "Connected to $serverUri and subscribed to $topic")

        } catch (e: MqttException) {
            Log.e("MqttHelper", "Error: ${e.message}")
            listener?.onError(e.message ?: "Unknown MQTT error")
        }
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient = null
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }
}
