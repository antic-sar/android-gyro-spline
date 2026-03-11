# Gyro Spline Android

Android (Jetpack Compose) implementation of a gyroscope/tilt-driven Spline scene, inspired by:
- iOS reference: [kapor00/gyro-spline](https://github.com/kapor00/gyro-spline)
- Vanilla JS version: [antic-sar/vanillajs-gyro-spline](https://github.com/antic-sar/vanillajs-gyro-spline)

## Overview

Add your GitHub uploaded video URL here:

`https://github.com/user-attachments/assets/your-video-id`

## What It Does

- Loads a Spline scene with local-first strategy (bundled `res/raw`) and URL fallback
- Uses device motion (`TYPE_GRAVITY` fallback to accelerometer)
- Auto-calibrates neutral hand angle
- Applies low-pass filtering + dead zone + smoothing for natural motion
- Rotates Spline object `Subject` in real time
- Uses black background behind the scene
- Shows animated loading overlay while scene runtime initializes

## Scene

- Editable source file: `https://app.spline.design/file/f5c6076a-3af1-48cf-9b4b-238e10c5b56b`
- Runtime export URL: `https://build.spline.design/SCHshn90bJB7fyKG6c41/scene.splinecontent`
- Bundled local runtime file: `app/src/main/res/raw/scene.splinecontent`
- Target object: `Subject`

If the object name is different in your scene, update `TARGET_OBJECT_NAME` in:

- `app/src/main/java/com/example/gyro_spline_android/MainActivity.kt`

## Loading Strategy

- By default, app tries loading local resource `R.raw.scene` first (`scene.splinecontent` in `res/raw`).
- If local resource is missing, it falls back to cloud URL (`build.spline.design`).
- To update scene quickly without network dependency, replace:
  - `app/src/main/res/raw/scene.splinecontent`

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

## Resume Behavior

- Default mode keeps the same `SplineView` instance on foreground return for faster reopen:
  - `RECREATE_SPLINE_VIEW_ON_FOREGROUND = false`
- If a device shows resume artifacts, you can switch to safer (slower) full reload on foreground:
  - `RECREATE_SPLINE_VIEW_ON_FOREGROUND = true`

## Credits

- Original iOS concept and implementation by Gabor Pribek (@kapor00)
- Android port by @antic-sar
