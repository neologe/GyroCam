# GyroCam

Android app that records video (all phone stabilization off) alongside a Gyroflow `.gcsv` gyro log,
for stabilizing the footage in [Gyroflow](https://gyroflow.xyz).

## Build
Push to GitHub; the **Build APK** workflow produces a `GyroCam-debug` artifact (Actions tab) containing `app-debug.apk`.
Or open the project in Android Studio and run it on a device.

## Use
1. Install the APK, grant camera permission, tap **Record**, then **Stop**.
2. Files are saved in `Download/GyroCam`: `GYRO_<timestamp>.mp4` and a matching `.gcsv`.
3. Load the video in Gyroflow; the sidecar log is detected automatically.

## Tuning
- `ORIENTATION` in `MainActivity.kt` maps IMU axes to the camera frame. Verify it once per phone in Gyroflow.
- Video is fixed at 1080p30 (`W`, `H`, `FPS` constants). No audio.
