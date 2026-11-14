# Ducky-Game

Ducky is an Android game built with Kotlin. Catch fish, track your progress, and
advance through levels.

## Requirements

- Android Studio
- Android SDK 36
- JDK 17 (required by the Android Gradle Plugin)

## Build and run

Open this project in Android Studio, let Gradle sync, and run the `app`
configuration on an emulator or Android device.

To build a debug APK from the command line:

```shell
./gradlew assembleDebug
```

On Windows, run `gradlew.bat assembleDebug`.

## Google Play submission readiness

The app targets API 36, the target required for new apps and updates submitted
to Google Play from August 31, 2026. It uses Google's User Messaging Platform
(UMP) to request updated consent at launch, show any required consent form
before ads, and expose a privacy-options button when UMP requires one.

The project still contains Google's official **test** AdMob app and ad-unit
IDs. Before release:

1. Create your AdMob app and ad units, then replace the sample IDs in
   `app/src/main/AndroidManifest.xml` and
   `app/src/main/java/com/geethdev/ducky/MainActivity.kt` with your own IDs.
   Never use live ads to test clicks.
2. Publish and configure the required UMP privacy and consent messages in
   AdMob for every region where the game will be available.
3. Publish a privacy policy that accurately describes the game and its ad SDK,
   add its public URL to the Play listing, set
   `privacy_policy_url` in `app/src/main/res/values/strings.xml`, and make the
   policy available from within the app.
4. Complete Play Console's Data safety, ads, target-audience, content-rating,
   and app-access declarations accurately. These depend on your actual data
   practices, audience, and distribution choices and cannot be completed by
   project code alone.
5. Test the release app bundle and consent/privacy flows on supported devices
   before submitting. Google Play approval is determined by the complete
   listing, declarations, app behavior, and review.

The banner is shown on the Levels screen and interstitials may appear between
levels; ads are not shown during gameplay.
