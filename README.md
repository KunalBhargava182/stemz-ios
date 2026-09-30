# stemz-ios

iOS version of the Android `stemz-app` (TAAL USB digital stethoscope recorder), built with
Kotlin Multiplatform + Compose Multiplatform. No Mac required.

## How it builds

- `shared/`: Kotlin Multiplatform module (UI in Compose, shared logic). Target: `iosArm64`.
- `iosApp/`: thin SwiftUI host. The Xcode project is generated in CI by XcodeGen from `iosApp/project.yml`.
- `.github/workflows/ios-build.yml`: builds on a GitHub macOS runner and uploads an **unsigned IPA** artifact.

## Install on iPhone (Windows)

1. GitHub → Actions → latest "iOS build" run → download `StemzApp-unsigned-ipa`, unzip it.
2. Open Sideloadly, drag in `StemzApp-unsigned.ipa`, enter your Apple ID, click Start.
3. First time only: iPhone Settings → General → VPN & Device Management → trust your Apple ID,
   and Settings → Privacy & Security → Developer Mode → On.

Free Apple ID signing expires after 7 days; re-run Sideloadly to refresh.
