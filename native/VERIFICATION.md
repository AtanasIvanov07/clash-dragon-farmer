# Verification — 15 September 2026

Release: `dev.s25.farmer`, version `0.3.0-device-test` (code 3), minimum Android 14/API 34. Built with SDK 35 and Java 17 language level. ARM64 for S25 and x86_64 for emulator testing.

## Executed checks

- Release, debug and Android test builds succeeded after the final fixes.
- The signed v0.2 release was installed on the emulator, its dragon count changed to 17 through the UI, and the signed v0.3 release installed over it. The app opened and retained 17. This verifies a saved setting through an actual update; it does not independently verify every calibration field.
- **27 JVM tests passed:** 20 state-machine tests and 7 parsing/spending-rule tests.
- **9 Android tests passed** on the Android 16/API 36 x86_64 emulator in 102.373 seconds: 2 troop-counter OCR tests, 6 smoke/regression tests and 1 complete native integration test. See `android-tests.txt`.
- The native integration completed **2 attacks and 4 verified wall upgrades**, alternating gold and elixir, using actual window screenshots, bundled OCR, Accessibility gestures and the floating START control against a synthetic companion app. See `emulator-session.jsonl`.
- Integration also rejected a mismatched final wall price, insufficient reserves, a tap covered by the floating overlay and a tap after STOP. It directly invoked both Volume Down key events and verified cancellation.
- Both large and small rendered troop counters were checked, including `x1` and `x0`. Resource OCR rejected separate rows as a single amount. Dimmed button templates and changed payment pixels were rejected.
- An earlier full passing run encountered an Android screenshot callback timeout and recovered using the single fresh-capture retry. This was observed in the emulator, not in Clash.
- Android lint: **0 errors, 16 warnings**, primarily programmatic UI/localization and dependency-version suggestions.
- APK signature verified (v2, RSA 3072), using the same certificate as v0.2. ZIP alignment passed with 16 KB native-library alignment. Bundled ARM64/x86_64 ELF alignment was checked earlier in the audit; those native libraries did not change in the final rebuild.
- Release manifest has WAKE_LOCK and an app-specific signature permission for AndroidX internal receivers. No Internet, network-state, contacts, SMS, camera, microphone, broad storage or SYSTEM_ALERT_WINDOW permission. Accessibility is enabled separately by the user and remains a broad grant.
- Release is not debuggable. The synthetic-scene package allowance is guarded by `BuildConfig.DEBUG` and is disabled in this release.

## Limits and unresolved device checks

The **Volume Down handler** passed. Attempts to deliver a virtual hardware key through the emulator console did not establish end-to-end delivery to Accessibility. Do not describe the physical button as device-verified; test it on the S25 with the floating STOP button available. `scripts/check_emulator_release.py hardware` records this additional check and currently does not pass in this emulator setup.

Not verified: installation on Samsung One UI, current live Clash visuals/flows, real troop-card depletion, army readiness between attacks, deployment effectiveness, or village camera return positions. Calibration and a supervised one-attack trial on the user's S25 remain necessary. An emulator fixture does not prove live farming reliability. These checks are not an independent security audit, and gameplay automation can lead to an account ban.

See `BUG-AUDIT.md` for findings and calibration migration instructions.
