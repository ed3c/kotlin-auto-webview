# iOS release runbook

1. Open `iosApp/iosApp.xcodeproj` on macOS with a supported Xcode version.
2. Change `PRODUCT_BUNDLE_IDENTIFIER`, Team, Marketing Version, and Build Number.
3. Add complete AppIcon assets and localized metadata.
4. Build the Kotlin framework through the Xcode build phase (`:composeApp:embedAndSignAppleFrameworkForXcode`).
5. Validate WKWebView login, cookies, media/full-screen behavior, background/foreground, memory pressure, and offline recovery on physical devices.
6. Archive with the Release scheme, run Organizer validation, then distribute to TestFlight.
7. Complete App Privacy details. Do not claim data is uncollected when a model endpoint or OpenClaw transport is configured.

The project uses HTTPS-only navigation by default and does not add broad App Transport Security exceptions.
