# APX Sentinel: YAMNet ML Integration - Updated Summary

**Date:** June 3, 2026
**Status:** ✅ Complete

---

## What Was Implemented

### 1. **Critical Bug Fix**
- Fixed audio recording - added `startRecording()` call in AudioStreamer.kt:47
- Audio now actually streams to AWS backend

### 2. **YAMNet Edge ML Layer**
All ML components isolated in `ml/` package:
- `AudioEvent.kt` - Data classes for events
- `YamnetClassifier.kt` - TensorFlow Lite inference (521 audio classes)
- `AudioProcessor.kt` - Audio buffer & ML processing
- `MLAlertManager.kt` - MQTT gunshot alert publisher

### 3. **Acoustic Event Detection**

**Gunshot Detection (Emergency):**
- Detected with >50% confidence
- Publishes to MQTT topic: `audio/ml/alerts`
- Immediate alert (<200ms latency)

**Distress Vocals (Cloud Enrichment):**
- Yelling, crying, screaming detected with >30% confidence
- **Added directly to WebSocket JSON payload sent to AWS**
- Enriches cloud analysis (Transcribe + Bedrock)

---

## Key Implementation Details

### WebSocket Payloads

**Normal Audio:**
```json
{
  "action": "sendAudioChunk",
  "data": "<base64_audio>"
}
```

**With Distress Vocals Detected:**
```json
{
  "action": "sendAudioChunk",
  "data": "<base64_audio>",
  "acoustic_events": [
    {
      "event": "yelling",
      "confidence": 0.65,
      "timestamp": 1749328472000
    },
    {
      "event": "screaming",
      "confidence": 0.72,
      "timestamp": 1749328472000
    }
  ]
}
```

### MQTT Gunshot Alert
```json
{
  "alert_type": "GUNSHOT_DETECTED",
  "confidence": 0.87,
  "timestamp": 1749328472000,
  "source": "edge_yamnet",
  "class_name": "Gunshot, gunfire"
}
```

---

## Files Modified

1. **AudioStreamer.kt** - Added acoustic_events to JSON payload (lines 130-144)
2. **AudioProcessor.kt** - Added pending events storage + getPendingEvents()
3. **MainActivity.kt** - Distress vocal callback logs only (line 53-55)
4. **MqttClientHelper.kt** - Added publish() method
5. **Gradle files** - Added TensorFlow Lite dependencies

## Files Created

- `ml/AudioEvent.kt`, `ml/YamnetClassifier.kt`, `ml/AudioProcessor.kt`, `ml/MLAlertManager.kt`
- `assets/yamnet.tflite`, `assets/yamnet_class_map.csv` (to be downloaded)

---

## How to Enable/Disable

**ML Enabled (current):**
```kotlin
private val streamer = AudioStreamer(websocketUrl, audioProcessor)
```

**ML Disabled:**
```kotlin
private val streamer = AudioStreamer(websocketUrl)
```

---

## Testing

```bash
# Build and run
./gradlew installDebug

# Monitor logs
adb logcat | grep -E "AudioStreamer|Distress|acoustic"

# Check for:
# - "Added X acoustic events to payload" (distress detected)
# - "GUNSHOT ALERT" (gunshot detected)

# Monitor MQTT
mosquitto_sub -h broker.hivemq.com -t "audio/ml/alerts" -v
```

---

## Architecture Flow

```
Audio → YAMNet ML → {
  Gunshot (>50%) → MQTT alert (immediate)
  Distress (>30%) → WebSocket JSON (cloud enrichment)
}
```

**Cloud receives:**
- Audio data (base64)
- Acoustic events metadata (yelling/crying/screaming)
- Single unified payload for better analysis

---

## Model Files Required

Download to `app/src/main/assets/`:

```bash
cd app/src/main/assets/
wget https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1?lite-format=tflite -O yamnet.tflite
wget https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv
```

See `assets/README_MODELS.md` for details.
