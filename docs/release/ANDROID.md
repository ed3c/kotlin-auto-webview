# Android release runbook

1. Set a unique `applicationId`, `versionCode`, and `versionName` in `composeApp/build.gradle.kts`.
2. Generate an upload keystore outside the repository and store credentials in CI secrets.
3. Add a release signing config through an untracked `secrets.properties` or CI environment variables.
4. Run `./gradlew :composeApp:bundleRelease`.
5. Execute Play pre-launch tests on API 24, 28, 33, and 36, covering login, media playback, rotation, process death, and WebView updates.
6. Complete Data Safety declarations. The intended production baseline is zero telemetry; document any backend or model endpoint before submission.
7. Enable Play App Signing and retain the upload key recovery procedure.

The current repository deliberately does not commit signing material or pretend that a debug build is store-ready.
