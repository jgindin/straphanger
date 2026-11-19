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

`version.properties` holds `versionName=<major>.<PR number>`, with major 0 while the app is in beta. Every pull request sets it to its own number (CI fails otherwise; Dependabot is exempt), so the version rises with each merge. The build derives `versionCode = major * 1_000_000 + pr * 1_000` (0.17 is 17000); the PR number must stay below 1000. Play requires a higher versionCode for every upload. The About screen shows the version.

## Build the bundle

    ./gradlew :androidApp:bundleRelease

Output: `androidApp/build/outputs/bundle/release/androidApp-release.aab`.

R8 minification and resource shrinking are on for release. Keep `androidApp/build/outputs/mapping/release/mapping.txt` for each uploaded version; upload it to Play Console (App bundle explorer > Downloads > Assets) or archive it, so crash stack traces can be deobfuscated.

## Checks

- Instrumented tests against the minified app:

      ./gradlew :androidApp:connectedReleaseTestAndroidTest -PtestBuildType=releaseTest -Pstraphanger.allowDebugSignedRelease=true

  Each build type installs under its own package: `com.straphanger.app` (release), `com.straphanger.app.debug` (debug) and `com.straphanger.app.releasetest` (releaseTest), so tests never touch an installed release build's rides. The tests run under the AndroidX Test Orchestrator with `clearPackageData`, so every test starts with empty app data and no manual `pm clear` is needed. To clear a test install by hand: `adb shell pm clear com.straphanger.app.releasetest` (or `.debug`).

  The tests run against the `releaseTest` build type: the release R8 configuration plus the keeps in `androidApp/proguard-test-rules.pro` that the separately minified test APK needs. This is not the exact shipping R8 graph, and the `release` build type never references the test rules. Never upload a `releaseTest` build.
  The tests cover ride logging, the sample Timeline import, and decoding `rides.json` in a fresh ViewModel. Backup export/import needs the system file picker and is not automated, so check it by hand on a release build before shipping.
- 16 KB page alignment: `zipalign -c -P 16 -v 4 androidApp/build/outputs/apk/release/androidApp-release.apk` should print `Verification successful`. The only native libraries are `libandroidx.graphics.path.so` for four ABIs.
- Version in the APK: `aapt2 dump badging <apk> | grep versionCode`.

## Releasing a version

1. Check `version.properties` holds the version to release (it is set by the last merged pull request), run `./gradlew syncIosVersion` (iOS reads `iosApp/Configuration/Version.xcconfig`; CI fails if it is stale), and add an entry to `CHANGELOG.md`.
2. Write the release notes in `androidApp/src/main/play/release-notes/en-US/default.txt` (500 characters at most).
3. Run `scripts/check-listing.sh` if you changed anything under `androidApp/src/main/play/listings`.
4. `jj commit -m "release: vX.Y.Z"`, then move `main` to that commit and push it.
5. Tag it and push the tag: `git tag v<versionName> <commit> && git push origin v<versionName>`. The tag must equal `v` plus `versionName`, or the workflow stops.

Pushing a `v*` tag runs `.github/workflows/release.yml`. It checks the tag against `version.properties`, runs `scripts/check-listing.sh`, runs the shared tests, builds the signed bundle, publishes it to the Play **internal testing** track, and attaches the AAB and `mapping.txt` to the run as workflow artifacts. Promote the release to other tracks in the Play Console.

### Gradle Play Publisher

The `com.github.triplet.play` plugin (4.1.1, the newest release on the Gradle plugin portal; it configures and registers `publishReleaseBundle` under AGP 9.5.1) is configured in `androidApp/build.gradle.kts`: track `internal`, app bundles, credentials from `STRAPHANGER_PLAY_CREDENTIALS` or `play-service-account.json` in the repo root (git-ignored). Listing files live in `androidApp/src/main/play` in the plugin's layout. Do not run `publishReleaseBundle` locally unless you mean to publish. Other tasks, such as `publishReleaseListing`, can update the listing text and graphics.

`./gradlew :androidApp:tasks` currently fails on a Compose Preview error unrelated to this plugin. Use `./gradlew :androidApp:help --task publishReleaseBundle` to confirm the task exists.

### GitHub secrets

Set these in the repository settings under Secrets and variables > Actions:

| Secret | Content |
| --- | --- |
| `UPLOAD_KEYSTORE_BASE64` | The upload keystore, base64 encoded: `base64 -w0 straphanger-upload.jks` |
| `UPLOAD_STORE_PASSWORD` | Keystore password (becomes `STRAPHANGER_UPLOAD_STORE_PASSWORD`) |
| `UPLOAD_KEY_ALIAS` | Key alias (becomes `STRAPHANGER_UPLOAD_KEY_ALIAS`) |
| `UPLOAD_KEY_PASSWORD` | Key password (becomes `STRAPHANGER_UPLOAD_KEY_PASSWORD`) |
| `PLAY_SERVICE_ACCOUNT_JSON` | The full JSON key of the Play service account |

## One-time Play Console setup (done by the maintainer)

- [ ] Create the app with package name `com.straphanger.app` in the Play Console.
- [ ] Enable Play App Signing. Play keeps the app signing key; the key from `keystore.properties` is the upload key.
- [ ] Upload the first AAB by hand to Internal testing. The Play API accepts uploads only after one manual upload exists. Build it with `./gradlew :androidApp:bundleRelease`.
- [ ] Complete the content rating questionnaire.
- [ ] Complete the target audience declaration (not designed for children).
- [ ] Complete the Data safety form from `docs/play/data-safety.md`.
- [ ] Complete the background location declaration from `docs/play/permissions-declaration.md`, including the recorded demo video.
- [ ] Create a service account with release permissions (Play Console > Users and permissions, and Google Cloud), and store its JSON key as the `PLAY_SERVICE_ACCOUNT_JSON` secret. Never commit it.
- [ ] Add the five GitHub secrets listed above.
- [ ] Enable GitHub Pages for the `docs/` folder and confirm that `PRIVACY_POLICY_URL` in the app points at the published privacy policy. Enter the same URL in the Play Console.
- [ ] Replace `<contact-email>` (and `<date>` in the privacy policy) wherever it appears in `docs/` with your real contact address.
- [ ] Add the store listing contact details and category in the Play Console. The listing text and graphics are uploaded by `publishReleaseListing` or entered by hand from `androidApp/src/main/play/listings/en-US`.
