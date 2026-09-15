# Native Dragon Farmer

Personal Android app for the user's TH18 dragon farming setup. The release APK runs on Android 14+ and includes ARM64 and x86_64 libraries. Samsung S25 gameplay needs user calibration and an actual device trial.

The APK includes calibration, screenshot capture through Accessibility, local template matching, bundled Google ML Kit Latin OCR, farming controls, gold/elixir wall spending checks, diagnostics, and STOP/Volume Down cancellation. It requires neither AutoJs6 nor root. Keep the screen on and Clash in the foreground.

## Privacy and boundaries

The merged release manifest removes Internet and network-state permissions. The only normal platform permission is WAKE_LOCK; AndroidX adds an app-specific signature permission for internal receivers. Accessibility requires explicit user activation and grants broad capabilities. Application code limits capture and gestures to `com.supercell.clashofclans`. Debug builds also accept the synthetic test scene; release builds do not.

Calibration screenshots, templates, settings and logs are stored in app-private files. Cloud backup/device-transfer rules exclude them. Export is a user-selected ZIP through Android's document picker. It includes game screenshots, settings and logs; sharing it is optional. The app bundles Google ML Kit libraries but needs no separately installed macro runtime. This is not an independent security audit or a guarantee against account bans.

## Build

Use SDK 35, Build Tools 35.0.0, JDK 17+ and Gradle 8.13 with Android Gradle Plugin 8.11.1. Run `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.

Portable Windows setup, from the repository root:

1. Run `python scripts/prepare_android.py`. It downloads and checksum-verifies the JDK, Gradle and Android command tools into `.toolchain/`.
2. Install platform 35 and Build Tools 35.0.0 in the SDK path in `.toolchain/paths.json`. The current Google CLI supports `android.exe --sdk=<path> sdk install platforms/android-35 build-tools/35.0.0`.
3. Run `python scripts/create_signing.py`, then `scripts/build_native.ps1 -Tasks :app:assembleRelease,:app:testDebugUnitTest,:app:lintDebug`.

Keep `native/signing/farmer.jks` and its password private and backed up locally: future updates require the same key. Signing files, build outputs, local SDK paths and toolchains are excluded from the source ZIP. A fresh checkout generates a different signing identity; uninstalling the previous app would then be necessary and erase its calibration.

## Validation

`RulesTest` and `FarmerTest` exercise the production state machine and spending rules. `DeviceSmokeTest` checks denied permissions, bundled OCR and rejection of dimmed template backgrounds. `CountOcrTest` checks troop quantities, including small/depleted counters. `NativeLoopTest` tests Android screenshots, OCR, gestures, payment rejection and cancellation against a synthetic `testscene` app. These simulated screens cannot verify actual Clash layouts, camera positions or army behavior. See `VERIFICATION.md` for the recorded results and `BUG-AUDIT.md` for fixes and remaining limits.

For emulator tests, install the debug app, its Android test APK and the `testscene` debug APK. Enable Dragon Farmer Accessibility on that emulator, then run instrumentation with `dev.s25.farmer.test/androidx.test.runner.AndroidJUnitRunner`. The fixture and test APK are not the farming app and must not be distributed as it.

The app stops on unknown screens, unreadable/changing numbers, unexpected confirmation prices, unverified deductions, coordinate changes, session limits, or leaving Clash. Defaults: 30 attacks/90 minutes, 1.5M combined loot, 1M reserves per resource, 10M maximum wall price, and two walls per return. Set the real dragon count before testing.
