# Quick Reference: Retrieving Logs from Android Device

## 📱 Log Location
```
/sdcard/Android/data/com.example.signex/files/SignexLogs/signex_log_YYYY-MM-DD_HH-mm-ss.txt
```

**No permissions required!** Uses app-specific storage.

## 🚀 Quick Retrieval Methods

### Method 1: ADB (Fastest)
```bash
# Pull latest log
adb pull /sdcard/Android/data/com.example.signex/files/SignexLogs/ .

# Or list files first
adb shell ls -lh /sdcard/Android/data/com.example.signex/files/SignexLogs/
```

### Method 2: Android Studio
1. View → Tool Windows → **Device File Explorer**
2. Navigate: `/sdcard/Android/data/com.example.signex/files/SignexLogs/`
3. Right-click log file → **Save As...**

**Note**: File Manager apps cannot access app-specific folders. Use ADB or Android Studio.

## 🔍 Quick Log Analysis

### Check if MediaPipe loaded
```bash
grep "✓\|✗" signex_log_*.txt
```

### Check detection rate
```bash
grep "Hand detection:" signex_log_*.txt | head -20
```

### Check overlay drawing
```bash
grep "Total landmarks drawn:" signex_log_*.txt | head -20
```

### Find errors
```bash
grep "ERROR\|CRITICAL" signex_log_*.txt
```

## 📊 What to Look For

### ✅ Good Signs
```
✓ Hand Landmarker loaded successfully
✓ Pose Landmarker loaded successfully
Hand detection: 2 hand(s) detected
Total landmarks drawn: 75
Inference result: Hello (confidence: 0.85)
```

### ⚠️ Warning Signs
```
✗ CRITICAL: Failed to load hand_landmarker.task
No hands detected in this frame
Total landmarks drawn: 0
Invalid hand landmark 5: (NaN, NaN)
```

## 📤 Sharing Logs

When sending logs, include:
1. **Full log file** (not just snippets)
2. **Device info** (model, Android version)
3. **Description** of the issue
4. **Screenshot** of the camera screen (if possible)

## 🎯 Common Issues

| Issue | Log Message | Solution |
|-------|-------------|----------|
| No overlay | `✗ Failed to load hand_landmarker.task` | Add .task files to assets/ |
| No detection | `No hands detected in this frame` | Better lighting, position hands clearly |
| Crash | `Invalid hand landmark: (NaN, NaN)` | Check MediaPipe initialization |
| Low confidence | `confidence: 0.25` | Ensure 30 frames buffered, stable hands |

## 🔧 Permissions

If logging doesn't work:
1. Settings → Apps → Signex → Permissions
2. Enable **Storage** permission
3. Restart app

---

**Need help?** Share the log file from `/sdcard/Documents/SignexLogs/`
