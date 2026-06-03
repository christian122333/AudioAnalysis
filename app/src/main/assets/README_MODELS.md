# YAMNet Model Files

This directory needs to contain the YAMNet TensorFlow Lite model files for acoustic event classification.

## Required Files

1. **yamnet.tflite** - The YAMNet model file (~3.5 MB)
2. **yamnet_class_map.csv** - Class label mappings (521 audio event classes)

## How to Download

### Option 1: Direct Download from TensorFlow Hub

1. **Download yamnet.tflite:**
   ```bash
   wget https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1?lite-format=tflite -O yamnet.tflite
   ```

2. **Download yamnet_class_map.csv:**
   ```bash
   wget https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv
   ```

### Option 2: Using TensorFlow Hub Python

```python
import tensorflow_hub as hub
import tensorflow as tf

# Load YAMNet model
model = hub.load('https://tfhub.dev/google/yamnet/1')

# Convert to TFLite
converter = tf.lite.TFLiteConverter.from_keras_model(model)
tflite_model = converter.convert()

# Save
with open('yamnet.tflite', 'wb') as f:
    f.write(tflite_model)
```

### Option 3: Pre-converted Model

Download the pre-converted TFLite model directly:
- **Model:** https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1
- **Labels:** https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv

## File Placement

After downloading, place both files in this directory:
```
app/src/main/assets/
├── yamnet.tflite
└── yamnet_class_map.csv
```

## Verification

To verify the files are correct:
- **yamnet.tflite** should be approximately 3.5 MB
- **yamnet_class_map.csv** should contain 521 rows (plus header) with columns: index, mid, display_name

## Important Classes for APX Sentinel

The following YAMNet classes are used for emergency detection:

### Gunshot Detection (Emergency - triggers immediate alert)
- Gunshot, gunfire
- Machine gun
- Artillery fire

### Distress Vocals (Metadata - enriches cloud payload)
- Screaming
- Wail, moan
- Crying, sobbing
- Baby cry, infant cry
- Whimper
- Yell
- Shout
- Battle cry

## Troubleshooting

If you get "File not found" errors when running the app:
1. Ensure both files are in the `app/src/main/assets/` directory
2. Clean and rebuild the project: `./gradlew clean build`
3. Check that the files are included in the APK:
   ```bash
   unzip -l app/build/outputs/apk/debug/app-debug.apk | grep yamnet
   ```

## License

YAMNet is released under Apache License 2.0 by Google.
- Model: https://tfhub.dev/google/yamnet/1
- Code: https://github.com/tensorflow/models/tree/master/research/audioset/yamnet
