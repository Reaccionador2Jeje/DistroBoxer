# Android application

DistroBoxer Android is a native Kotlin + Jetpack Compose application.

## Current state

The initial app shell displays the supported distribution catalog. Install actions are deliberately disabled until the Rust engine, rootfs installer, and PRoot runtime are integrated.

## Build

Requirements:
- JDK 17
- Android SDK with platform 35
- Gradle 8.9

From the repository root:

```sh
gradle :android:app:assembleDebug
```

The debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

The application currently has no storage or network permissions because the preview UI does not need them.
