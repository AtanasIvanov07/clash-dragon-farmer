# Dragon Farmer for Android

An experimental, standalone Android accessibility app for repetitive Clash of Clans farming with dragons and optional gold/elixir wall upgrades. It runs on the phone without root, AutoJs6, or a connected computer.

> [!CAUTION]
> This is an unofficial community project and is not affiliated with, endorsed by, or sponsored by Supercell. Automated gameplay may violate the game's rules and can result in account penalties. The current release was tested against synthetic game screens, not live Clash of Clans. Use a supervised test and a nonessential account first.

## Current status

Version 0.3 is a device-test release for Android 14 and newer. It includes local OCR, screen calibration, guarded taps, resource reserves, wall-price verification, session limits, a floating STOP button, and Volume Down cancellation.

The final automated run passed 27 JVM tests and 9 Android tests, including two synthetic attacks and four verified synthetic wall upgrades. Samsung One UI and current live-game behavior still need field testing. Read the [verification report](native/VERIFICATION.md) and [bug audit](native/BUG-AUDIT.md) for the exact scope.

## Install

1. Download `Dragon-Farmer.apk` from the repository's latest GitHub Release.
2. Install it, open Dragon Farmer, and enable its Accessibility service.
3. Set Clash to English and landscape. Prepare dragons and leave one builder free.
4. Follow **Capture game screens**, then enter your actual dragon count.
5. Run **Check recognition**, followed by **Test one attack + wall check** under supervision.

Use the floating **STOP** button or Volume Down to end a session. Keep Clash open, the phone unlocked, and the floating control away from game buttons and deployment points.

Updating from 0.2 requires recapturing **Home village** and **Opponent scouting** because 0.3 adds a static village landmark and troop-count region.

## Privacy and permissions

The release APK has no Internet, contacts, SMS, camera, microphone, broad-storage, or system-overlay permission. Calibration images, settings, and logs stay in app-private storage unless you explicitly export diagnostics.

Accessibility is still a powerful permission: it allows the app to capture the Clash window and send gestures. Release code restricts actions to the Clash of Clans package and stops when screens, prices, balances, coordinates, or window state do not match calibration.

## Build from source

The Android project is in [`native/`](native/README.md). It requires SDK 35, Build Tools 35.0.0, JDK 17+, Gradle 8.13, and Android Gradle Plugin 8.11.1.

On Windows, the included scripts can prepare a local toolchain and build it:

```powershell
python scripts/prepare_android.py
python scripts/create_signing.py
./scripts/build_native.ps1 -Tasks @('assembleRelease','testDebugUnitTest','lintDebug')
```

The signing script generates a private local key that is excluded from Git. Back it up if you want future APK updates to install over your build.

## Contributing

Bug reports should include the phone model, Android/One UI version, app version, the stage that stopped, and exported diagnostics with personal details reviewed before upload. Do not upload signing keys or unreviewed screenshots.

Licensed under the [MIT License](LICENSE).

Clash of Clans and Supercell are trademarks of Supercell Oy.
