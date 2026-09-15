# Native app bug review — version 0.3.0

Reviewed the standalone Android implementation, calibration UI and persistence, image/OCR handling, farming state machine, Accessibility lifecycle, release manifest and packaging. The legacy AutoJs6 implementation is outside this review. The user confirmed that the S25 APK has not yet been installed or tried.

## Findings fixed

| Priority | Problem in 0.2 | Change in 0.3 |
|---|---|---|
| High | The fixed Attack button could match while the village camera had moved, making saved wall coordinates point elsewhere. | Home calibration now includes a static landmark near the walls. Wall selection requires that landmark at the calibrated position plus the Home button. A mismatch stops before selection. |
| High | A wall price was checked before the final tap, but the tap itself only rechecked button templates. Resources could also change between initial balance reading and payment. | The payment adapter checks current price, current balance, reserve and cap, then takes another screenshot and requires unchanged price/balance pixels and both confirmation templates before tapping. Post-payment verification uses that fresh balance. |
| High | Attempted deployment taps were counted as deployed dragons, even on forbidden ground. Army size was not read from the game. | A calibrated troop-count crop verifies enough dragons before attacking. Each deployment must reduce the count by one; rejected taps try other marked points. Unknown or unexpected count changes stop further deployment. |
| High | An absent post-upgrade wall title could still be counted as a verified upgrade if resources changed by the expected amount. | If the game deselects the wall, the app reselects it at the verified village position. It must read the old level plus one before counting the upgrade or spending again. |
| High | Posted screenshot/gesture callbacks could execute after STOP, timeout, or a new session. Old completion/UI callbacks could affect newer controls. | Worker session tokens are checked again on the Android main thread; timed-out futures are cancelled before dispatch. Starting another session and editing settings remain blocked while the prior worker exits. Completion and overlay removal check the session token. |
| Medium | Android screenshot callbacks sometimes timed out. Main-thread root lookup could also block, and rate-limit timing was measured before the actual request. | Root/package lookup stays on the worker; the main thread checks the same focused window and cancellation immediately before dispatch. Screenshot throttling starts at actual submission. One read-only retry requests a new frame after a timeout; taps/payments are never replayed. Errors now identify screenshot versus gesture timeout and save a stack trace. |
| High | Window-only screenshots deliberately omit Accessibility overlays, so a valid-looking tap could hit the floating control or another window. | The gesture adapter checks each gesture's screen bounds against higher visible windows and stops when obstructed. It also rejects non-phone displays and active magnification that invalidate coordinates. |
| Medium | Joining separate OCR rows with spaces could produce a valid-looking grouped amount. Mixed grouping separators were accepted. | Amount OCR preserves row breaks; parsing rejects multiple rows, inconsistent grouping, and oversized initial groups. |
| Medium | STOP or OCR timeout could recycle a bitmap while ML Kit still used it asynchronously. | OCR owns an independent bitmap until its actual task completes, including when the waiting worker has already stopped. |
| Medium | A cold OCR model exceeded the fixed eight-second limit during Android startup. Sparse `x1` crops could also be interpreted upside down as `LX`. | The first request gets a 30-second bound; subsequent reads retain eight seconds and interruption still ends the wait. Troop-counter preprocessing adds an upright orientation label outside the untouched crop, then removes that label from the result. No guessed digit substitutions are used. |
| Medium | Cancelling a recapture could leave some new fields mixed with old fields and appear complete. Wall calibration could also block attack-only mode. | Starting a recapture invalidates that stage's old fields. Attack-only completeness ignores wall-only fields; invalid calibration containers produce a clear error. |
| Medium | A very fast Next-base transition could finish between captures, making the app wait for a disappearance that had already happened. | After Next, allow a settling interval, then require scouting and stable loot again. Skip limits and screen guards remain in force. |
| Medium | STOP could disappear off screen after dragging; key-down was consumed but its paired key-up was not; stopped sessions left an old result report. | Dragging is clamped to the display, both Volume Down events are handled, and STOP writes the latest result. |
| Medium | A settings dialog or file picker opened while idle could return while a session had started. | Save/export callbacks recheck idle state; diagnostics also validate window origin as well as dimensions. |
| Low | The floating STOP label could wrap after the overlay was created in portrait and Clash opened in landscape. Idle service shutdown could overwrite the last session report. | Buttons have reserved widths and single-line labels. Idle Accessibility interruption/shutdown preserves the previous result. |

## Calibration migration

Version 0.2 profiles need **Home village** and **Opponent scouting** recaptured to add `home_anchor` and `dragon_count`. The release keeps the existing package and signing key, so installing the updated APK over the prior release preserves other settings/calibration. The static landmark must not be a wall that will change appearance during upgrades. Keep number crops tight and avoid animated decorations in template crops.

## Android and game assumptions checked

Android documents that the active window can come from any logical display and that injected gestures may be affected by magnification; this supports explicitly rejecting those coordinate-changing modes. See [AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).

Clash separates regular and Ranked battles. Calibrate the repeatable regular farming battle flow; this app does not automate ranked schedules or league limits. See [Supercell's battle-mode update](https://supercell.com/en/games/clashofclans/blog/release-notes/get-ready-for-ranked-update/).

## Remaining limits

This review does not prove the APK free of bugs. Actual Samsung One UI installation, current Clash visuals, readable troop counters (including the depleted-card state), camera restoration, and gameplay performance still need the first supervised S25 test. Pixel comparison intentionally stops if payment-critical digits animate or change. A bounded in-flight gesture can finish before Android delivers STOP; no new gestures are queued after cancellation checks fail. Another enabled Accessibility service can affect hardware-key delivery. The floating STOP control remains available.

The app does not recover blindly from maintenance, disconnections, unknown dialogs, or unreadable values. It pauses the farming task by stopping and records the error. Spells, heroes, Clan Castle troops and tactical attack quality are not automated. No anti-ban or independent security-audit claim is made.

See `VERIFICATION.md` for the final executed checks and their scope.
