# Setup

## Requirements
- Android Studio Ladybug (2024.2) or newer, or command line tools
- JDK 17+ (the Gradle wrapper runs on 17 or 21)
- Android SDK Platform 35, Build-Tools 35
- A device or emulator running Android 8.0 (API 26) or newer; a physical device is recommended for the camera

## Build and run
```bash
cd PokerVisionAI
# Point Gradle at your SDK if not using Android Studio
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug                 # build APK -> app/build/outputs/apk/debug/
./gradlew installDebug                  # install on connected device
./gradlew test                          # JVM unit tests (all modules)
./gradlew connectedDebugAndroidTest     # instrumented tests (Room DAO/migration, Compose UI)
```
The first build downloads Gradle 8.11.1 and all dependencies, and writes `core/database/schemas/1.json` — commit it.

## Key versions (gradle/libs.versions.toml)
AGP 8.7.3 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 · Compose BOM 2024.12.01 · Hilt 2.52 · Room 2.6.1 · CameraX 1.4.1 · Media3 1.5.0 · DataStore 1.1.1 · Navigation 2.8.5 · Coil 2.7.0

## Permissions
Only `CAMERA` (runtime). No internet, no microphone, no storage permissions (the Photo Picker grants per-item access).

## Switching language
Home → translate icon → System / English / فارسی. On Android 13+ it also appears in system Settings → Apps → Poker Vision AI → Language.
