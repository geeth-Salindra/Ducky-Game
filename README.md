# Ducky-Game

Ducky is an Android game built with Kotlin. Catch fish, track your progress, and
advance through levels.

## Requirements

- Android Studio
- Android SDK 35
- JDK 17 (required by the Android Gradle Plugin)

## Build and run

Open this project in Android Studio, let Gradle sync, and run the `app`
configuration on an emulator or Android device.

To build a debug APK from the command line:

```shell
./gradlew assembleDebug
```

On Windows, run `gradlew.bat assembleDebug`.

## AdMob setup

The app currently uses Google's official **test** app, banner, and rewarded ad
unit IDs. The banner appears only on the Levels screen. After completing a
level, the player must watch a rewarded ad to unlock the next level; if the ad
is unavailable or dismissed early, they remain on the win screen and can retry.
Ads are hidden during active gameplay.

Before publishing, create your own AdMob app and ad units, replace the sample
IDs in `app/src/main/AndroidManifest.xml` and
`app/src/main/java/com/geethdev/ducky/MainActivity.kt`, and configure consent
and privacy disclosures for the regions where the app is distributed. Keep
Google's test IDs during development and never use live ads to test clicks.
