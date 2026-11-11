# Releasing Straphanger

## Upload key (one time, done by the maintainer)

Create the upload keystore yourself and keep the passwords. Never commit it.

    keytool -genkeypair -v -keystore ~/keys/straphanger-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000

Create `keystore.properties` in the repo root (it is git-ignored, as are `*.jks`, `*.keystore` and `play-service-account*.json`):

    storeFile=/home/you/keys/straphanger-upload.jks
    storePassword=...
    keyAlias=upload
    keyPassword=...

Use another location with `-Pstraphanger.keystoreProperties=/path/to/file`. If the file is missing, the build reads the env vars `STRAPHANGER_UPLOAD_STORE_FILE`, `STRAPHANGER_UPLOAD_STORE_PASSWORD`, `STRAPHANGER_UPLOAD_KEY_ALIAS` and `STRAPHANGER_UPLOAD_KEY_PASSWORD`.

Without either, `assembleRelease` and `bundleRelease` fail with `Release signing not configured: see docs/RELEASING.md`. For local checks only, add `-Pstraphanger.allowDebugSignedRelease=true` to sign with the debug key. Never upload such a build.

## Version

`version.properties` holds `versionName=MAJOR.MINOR.PATCH`. The build derives `versionCode = major * 1_000_000 + minor * 1_000 + patch` (0.1.0 is 1000). Minor and patch must stay below 1000. Edit the file to bump the version; Play requires a higher versionCode for every upload.

## Build the bundle

    ./gradlew :androidApp:bundleRelease

Output: `androidApp/build/outputs/bundle/release/androidApp-release.aab`.

R8 minification and resource shrinking are on for release. Keep `androidApp/build/outputs/mapping/release/mapping.txt` for each uploaded version; upload it to Play Console (App bundle explorer > Downloads > Assets) or archive it, so crash stack traces can be deobfuscated.

## Checks

- Instrumented tests against the minified app (clear app data first):

      adb shell pm clear com.straphanger.app
      ./gradlew :androidApp:connectedReleaseAndroidTest -PtestBuildType=release -Pstraphanger.allowDebugSignedRelease=true

  With `-PtestBuildType=release`, `androidApp/proguard-test-rules.pro` is added to the app's R8 run. It keeps shared library classes the test APK needs and is not used for shipping builds.
- 16 KB page alignment: `zipalign -c -P 16 -v 4 androidApp/build/outputs/apk/release/androidApp-release.apk` should print `Verification successful`. The only native libraries are `libandroidx.graphics.path.so` for four ABIs.
- Version in the APK: `aapt2 dump badging <apk> | grep versionCode`.
