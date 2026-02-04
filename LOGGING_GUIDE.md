# SignEx Logging and Debugging Guide

## Overview
The SignEx app now includes a comprehensive file logging system that writes detailed logs to your device's external storage. This helps diagnose issues with landmark detection, overlay rendering, and model inference.

## Log File Location

Logs are saved to **app-specific external storage** (no permission required):
```
/storage/emulated/0/Android/data/com.example.signex/files/SignexLogs/
```

You can access this folder using:
- **Android Studio Device File Explorer** (View → Tool Windows → Device File Explorer)
- **ADB**: `adb pull /sdcard/Android/data/com.example.signex/files/SignexLogs/`

**Note**: This location is automatically accessible without requiring storage permissions on Android 10+. The logs are deleted when you uninstall the app.

## Log File Format

Log files are named: `signex_log_YYYY-MM-DD_HH-mm-ss.txt`

Example: `signex_log_2026-02-04_12-45-30.txt`

## What Gets Logged

### 1. **Initialization Logs**
- MediaPipe landmarker loading status (Hand, Pose, Face)
- TensorFlow Lite model loading
- Label mapping loading
- Log file path

### 2. **Frame Processing Logs**
- Number of hands detected per frame
- Hand handedness (Left/Right) and confidence scores
- Number of pose landmarks detected
- Timestamp of each frame

### 3. **Feature Extraction Logs**
- Hand landmark extraction details
- Pose landmark extraction (15 points)
- Feature vector composition

### 4. **Buffer Management Logs**
- Buffer status (e.g., "Buffering 10/30 frames")
- When buffer is cleared (no hands detected)
- When buffer reaches full sequence length (30 frames)

### 5. **Inference Logs**
- Gesture prediction results
- Confidence scores
- Model inference errors

### 6. **Overlay Drawing Logs**
- Canvas dimensions
- Number of landmarks being drawn
- Coordinate validation errors
- Drawing errors with stack traces

## How to Retrieve Logs from Your Device

### Method 1: Using Android Studio (Recommended)
1. Open Android Studio
2. Go to **View → Tool Windows → Device File Explorer**
3. Navigate to `/sdcard/Android/data/com.example.signex/files/SignexLogs/`
4. Right-click on the log file → **Save As...**

### Method 2: Using ADB (Command Line)
```bash
# List all log files
adb shell ls /sdcard/Android/data/com.example.signex/files/SignexLogs/

# Pull the latest log file
adb pull /sdcard/Android/data/com.example.signex/files/SignexLogs/signex_log_2026-02-04_12-45-30.txt

# Pull all logs
adb pull /sdcard/Android/data/com.example.signex/files/SignexLogs/ ./logs/
```

**Note**: File Manager apps typically cannot access app-specific folders. Use Android Studio or ADB for easiest access.

## Permissions Required

**No storage permissions required!** The app uses app-specific external storage which is automatically accessible on Android 10+ without runtime permissions. Logs are automatically deleted when you uninstall the app.

## Log Rotation

- Maximum log file size: **5 MB**
- When a log file exceeds 5 MB, a new file is created
- Only the **last 10 log files** are kept (older ones are automatically deleted)

## Troubleshooting Landmark Overlay Issues

If landmarks are not showing on the camera preview, check the logs for:

### 1. **MediaPipe Initialization**
Look for:
```
✓ Hand Landmarker loaded successfully
✓ Pose Landmarker loaded successfully
```

If you see:
```
✗ CRITICAL: Failed to load hand_landmarker.task
```
**Solution**: Ensure `.task` files are in `app/src/main/assets/`

### 2. **Detection Results**
Look for:
```
Frame processed: Hands=2, Pose=1, Timestamp=1738656789123
Hand detection: 2 hand(s) detected
  Hand 0: Left (confidence: 0.95)
  Hand 1: Right (confidence: 0.92)
Pose landmarks extracted: 15 points
```

If you see:
```
No hands detected in this frame
No pose landmarks detected
```
**Solution**: 
- Ensure good lighting
- Position hands clearly in camera view
- Check if MediaPipe models loaded successfully

### 3. **Overlay Drawing**
Look for:
```
Drawing overlay - Canvas: 1080x2400, Image: 640x480
Drawing 2 hand(s)
Drawing pose with 33 landmarks
Total landmarks drawn: 75
```

If you see:
```
OVERLAY ERROR
Invalid hand landmark 5: (NaN, NaN)
```
**Solution**: This indicates a coordinate calculation issue - check the logs for the root cause

### 4. **ExtractionResult Updates**
Look for:
```
ExtractionResult updated: Hands=2, Pose=1, Gesture=Buffering (15/30)
```

If this line is missing, the extraction result is not being passed to the UI.

## Enhanced Overlay Features

The new overlay includes:

### Visual Improvements
- **Brighter colors** for better visibility
  - Hands: Bright cyan (#00E5FF)
  - Pose: Bright red (#FF1744) with yellow connections
  - Face: Bright green (#76FF03)
- **Larger landmark dots** (8-14px)
- **Thicker connection lines** (4-5px)
- **Hand bone structure** with proper finger connections
- **Debug counter** showing total landmarks drawn

### Error Handling
- Coordinate validation (checks for NaN, Infinity, out-of-bounds)
- Try-catch blocks around each drawing operation
- Detailed error logging with stack traces
- Fallback error indicator on screen

## Key Log Messages to Look For

### Success Indicators ✅
```
========== SignLanguageClassifier Initialization Started ==========
✓ Hand Landmarker loaded successfully
✓ Pose Landmarker loaded successfully
Frame processed: Hands=2, Pose=1
Hand detection: 2 hand(s) detected
Inference result: Hello (confidence: 0.85)
Total landmarks drawn: 75
```

### Warning Signs ⚠️
```
✗ CRITICAL: Failed to load hand_landmarker.task
No hands detected in this frame
Invalid hand landmark 5: (NaN, NaN)
Buffer status: 5/30 frames (stuck at low number)
```

### Critical Errors ❌
```
CRITICAL: Failed to load hand_landmarker.task
java.io.FileNotFoundException: hand_landmarker.task
Landmarker setup failed
Frame analysis error
OVERLAY ERROR
```

## Sending Logs for Support

When reporting issues, please include:

1. **Full log file** from `/sdcard/Documents/SignexLogs/`
2. **Description** of the issue (e.g., "landmarks not showing")
3. **Device info** (model, Android version)
4. **Steps to reproduce** the issue

## Performance Notes

- Logging adds minimal overhead (~1-2ms per frame)
- DEBUG level logs are verbose - they log every frame
- INFO level logs are for significant events (initialization, inference results)
- ERROR level logs include full stack traces

## Disabling Logging

If you want to disable file logging for performance:

1. Comment out the `fileLogger` calls in:
   - `SignLanguageClassifier.kt`
   - `CameraScreen.kt`
   - `LandmarkOverlay.kt`

2. Or modify `FileLogger.kt` to make all methods no-ops

## Log Analysis Tips

### Finding Initialization Issues
```bash
grep "Initialization" signex_log_*.txt
grep "✗" signex_log_*.txt  # Find all errors
```

### Tracking Detection Rate
```bash
grep "Hand detection:" signex_log_*.txt | wc -l  # Count frames with hands
grep "No hands detected" signex_log_*.txt | wc -l  # Count frames without hands
```

### Finding Inference Results
```bash
grep "Inference result:" signex_log_*.txt
```

### Checking Overlay Performance
```bash
grep "Total landmarks drawn:" signex_log_*.txt
```

## Next Steps

After retrieving the logs:

1. **Check initialization** - Are all MediaPipe models loaded?
2. **Verify detection** - Are hands/pose being detected?
3. **Confirm overlay** - Is the overlay drawing landmarks?
4. **Review errors** - Are there any exceptions or warnings?

Share the log file for detailed analysis and troubleshooting assistance.
