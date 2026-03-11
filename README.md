# Gyro Spline Android

Android (Jetpack Compose) implementation of a gyroscope/tilt-driven Spline scene, inspired by:

- iOS reference: [kapor00/gyro-spline](https://github.com/kapor00/gyro-spline)
- Vanilla JS version: [antic-sar/vanillajs-gyro-spline](https://github.com/antic-sar/vanillajs-gyro-spline)

## What It Does

- Loads a Spline scene from URL using `design.spline:spline-runtime`
- Uses device motion (`TYPE_GRAVITY` fallback to accelerometer)
- Auto-calibrates neutral hand angle
- Applies low-pass filtering + dead zone + smoothing for natural motion
- Rotates Spline object `Subject` in real time
- Uses black background behind the scene

## Scene

- Editable source file: `https://app.spline.design/file/f5c6076a-3af1-48cf-9b4b-238e10c5b56b`
- Runtime export URL: `https://build.spline.design/SCHshn90bJB7fyKG6c41/scene.splinecontent`
- Target object: `Subject`

If the object name is different in your scene, update `TARGET_OBJECT_NAME` in:

- `app/src/main/java/com/example/gyro_spline_android/MainActivity.kt`

## Requirements

- Android Studio (latest stable)
- Physical Android device for real sensor behavior
- USB debugging enabled

## Run

1. Open project in Android Studio.
2. Connect Android device via USB.
3. Select device and run app.

Or from terminal:

```bash
./gradlew installDebug
adb shell am start -n com.example.gyro_spline_android/.MainActivity
```

## Motion Tuning

Adjust these constants in `MainActivity.kt`:

- `MAX_ROTATION_X`, `MAX_ROTATION_Y`: max scene tilt angle
- `ROTATION_SENSITIVITY_X`, `ROTATION_SENSITIVITY_Y`: responsiveness
- `ROTATION_SMOOTHING_FACTOR`: smoothness vs responsiveness
- `SENSOR_LPF_ALPHA`: sensor noise filtering
- `DEAD_ZONE`: ignores tiny hand shake

## Credits

- Original iOS concept and implementation by Gabor Pribek (@kapor00)
- Android port by @antic-sar
