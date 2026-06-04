package com.example.audioanalysis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.audioanalysis.ml.*
import com.example.audioanalysis.ui.theme.AudioAnalysisTheme

class MainActivity : ComponentActivity() {
    // Initialize ML components
    private val yamnetClassifier by lazy { YamnetClassifier(applicationContext) }
    private val audioProcessor by lazy { AudioProcessor(yamnetClassifier) }
    private lateinit var mlAlertManager: MLAlertManager

    private val mqttHelper = MqttClientHelper("tcp://broker.hivemq.com:1883") // Replace with your AWS IoT endpoint

    // Pass ML processor to streamer (optional parameter)
    private val streamer by lazy {
        AudioStreamer(
            applicationContext,
            "wss://1m36b07xi1.execute-api.us-east-2.amazonaws.com/production",
            audioProcessor
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize ML components
        yamnetClassifier.initialize()
        mlAlertManager = MLAlertManager(mqttHelper)

        // Setup ML callbacks
        audioProcessor.setCallback(object : AudioProcessor.AudioProcessorCallback {
            override fun onGunshot(event: ClassifiedEvent) {
                mlAlertManager.publishGunshot(event)
                // Trigger audio feedback on device
                streamer.writeToSpeaker(170)
                streamer.soundIdValue = 170
            }

            override fun onDistressVocal(event: ClassifiedEvent) {
                // Distress vocals now sent in cloud JSON payload, not MQTT
                Log.d("MainActivity", "Distress vocal detected: ${event.event.className} (${event.event.confidence})")
            }

            override fun onClassification(events: List<ClassifiedEvent>) {
                // Could update UI here if needed
            }
        })

        enableEdgeToEdge()
        setContent {
            AudioAnalysisTheme {
                var status by remember { mutableStateOf("Disconnected") }
                var mqttMessage by remember { mutableStateOf("Waiting for MQTT...") }
                var isStreaming by remember { mutableStateOf(false) }
                val context = LocalContext.current

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        streamer.startStreaming()
                        isStreaming = true
                    } else {
                        status = "Permission Denied"
                    }
                }

                LaunchedEffect(Unit) {
                    streamer.listener = object : AudioStreamer.StreamListener {
                        override fun onConnected() { status = "Connected" }
                        override fun onDisconnected() { 
                            status = "Disconnected"
                            isStreaming = false
                        }
                        override fun onError(message: String) { status = "Error: $message" }
                        override fun onMessage(text: String) { /* Handle server response */ }
                    }

                    mqttHelper.listener = object : MqttClientHelper.MqttListener {
                        override fun onConnected() { Log.d("MainActivity", "MQTT Connected") }
                        override fun onMessageReceived(topic: String, message: String) {
                            mqttMessage = message
                        }
                        override fun onError(message: String) {
                            Log.e("MainActivity", "MQTT Error: $message")
                        }
                    }
                    // Replace "audio/results" with your actual subscription topic
                    Thread { mqttHelper.connect("audio/results") }.start()
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Status: $status", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "MQTT: $mqttMessage", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            if (isStreaming) {
                                streamer.stopStreaming()
                            } else {
                                when (PackageManager.PERMISSION_GRANTED) {
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
                                        streamer.startStreaming()
                                        isStreaming = true
                                    }
                                    else -> {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            }
                        }) {
                            Text(if (isStreaming) "Stop Streaming" else "Start Streaming")
                        }
                    }
                }
            }
        }
    }
}
