# Phonly Phone — continuation notes

Public repo: https://github.com/StephenSwanick/PhonlyPhone (GPL-3).  
`origin` = PhonlyPhone. `upstream` = FossifyOrg/Phone.  
`main` = working branch. `clone` = frozen Fossify at `862bcfc8` — do not commit on `clone`.  
`DialerBackup` = pre-AppConfig spike (hardcoded numbers + screening reject). Do not commit on `clone`.

This app stays its own public repo. Not inside PhonlyV1 / Phonly Code.  
Kotlin namespace stays `org.fossify.phone`. Install id is `co.phonly.phone` (debug and release; no `.debug` suffix).  
Messages / SMS is a later repo (PhonlyMessages, `co.phonly.messages`). Do not put SMS here.

## Status (2026-08-30)

Missed-call overlay **lab-proven on AAAAY** (debug, Esper Home, KSP Appear on top), then signed **1.1.0 / `VERSION_CODE` 24** uploaded. Esper library: `co.phonly.phone`, app `344804b8-04e5-478c-be1a-decc284a0c43`, version `3b07140c-5007-429e-88ca-390f95bb0b35`. **PhonlyV2 - DEV published v25.0** SHOWing it. Esper UI: version `1.1.0`, version code `24`, release tag `0`. Signed 1.0.0 / 23 remains in the library unused.

**Lab:** one white top bar; same person → count; second caller → “and others”. Tap / Recents clears. Allowlist drops stay silent. Phone **powered off** → carrier VM, **no** overlay after boot (correct: `CallService` never ran). Operator saw a shade **voicemail** notification after boot (T-Mobile VVM / system). Kids on live Home may not have shade.

**Look:** one white heads-up bar at the top (below status icons). Photo or Phone icon; **Missed call** + name. Same person updates in place (`Mom, 3 missed calls`). Different people stay one bar (`Mom and 1 other`). No bubbles, no stack, no swipe-to-dismiss, no timeout.

**Dismiss:** tap the bar or open Recents. Miss stays in Recents. Declined calls (`REJECTED` / `LOCAL`) do not show a bar.

**Grant:** fleet KSP Permission Controls Appear on top → `co.phonly.phone` and `co.phonly.messages`. Do not open Settings if the grant is missing.

**Code:** `SYSTEM_ALERT_WINDOW` + `MissedCallOverlay` / `MissedCallOverlayService`. Persist unacked misses; re-show after process death / boot **only if this app already recorded the miss**. Hook `CallService.onCallRemoved`.

**Do not:** fleet CONVERGE or copy to live Home unless asked. Debug 24 and signed 24 **cannot** overwrite each other. After Esper install, set default Phone again (`ROLE_DIALER` drops on uninstall). Operator was to CONVERGE **AAAAY only** after debug was gone — confirm on-device version before treating signed 24 as live on that unit.

## Status (2026-08-20, later)

**Signed Phone 1.0.0 (`VERSION_CODE` 23) is in the Esper app library and on AAAAY via V2-DEV.** Allowlist still comes from Mongo → Esper AppConfig `allowlist_json`. Same JSON worked on this signed APK (no rebuild).

- Package: `co.phonly.phone`. Launcher **Phone**. Not minified.
- Signing key: `%LOCALAPPDATA%\phonly-phone-signing\` (not git, not Dropbox). Operator has a copy elsewhere. Lose it and we cannot update this package in place.
- Debug 1.11.1 and this release **cannot** overwrite each other. To swap certs: V2-DEV `allow_app_uninstallation: true`, take Phone **off** the blueprint, CONVERGE, uninstall on device, put signed Phone back, CONVERGE.
- Scripts (Phonly Code): `esper-phone-upload-v2-dev.mjs`, `esper-phone-replace-signed-aaaaay.mjs`.

Live Home3, hide Samsung Dialer, and fleet CONVERGE are **not** done.

## What already works

- Launcher **Phone**, About name Phonly Phone, NOTICE/GPL-3 kept.
- No hardcoded numbers. Enforcement is AppConfig plus 911 / Android emergency.
- Outgoing: not-allowed → toast **Call not allowed**.
- Incoming (this app must be the **default Phone app**): not-allowed → `IncomingAllowlistDrop` answers and hangs up. T-Mobile: no voicemail; ~1s recents row; brief status-bar blink. Tapping recents to call back is still blocked.
- Fossify “fake version / Play Store” check rewritten at build time so `co.phonly.phone` is accepted.
- Outgoing intents target this app’s `DialerActivity`.
- Default launcher icon is the Phonly brandmark. Fossify color-icon variants in Customize are still the old handset.
- Crash fix: keep `color_incoming_call` in `colors.xml`.
- Missed-call top bar (debug 1.1.0 / 24, AAAAY 2026-08-30): allowlisted miss over Home; count + “and others”; tap/Recents clears. Powered-off call is VM, not this bar.

## Allowlist

- Source of truth: Mongo `phonly.devices.allowlist` (Phonly Code). No Mongo in this APK.
- Delivery: Esper managed config string key **`allowlist_json`** on package **`co.phonly.phone`**.
- Shape: JSON **string** whose contents are an array:

```json
[{"e164":"+17046180435","label":"Test","voice":true,"sms":true}]
```

Esper’s device AppConfig box must be:

```json
{
  "allowlist_json": "[{\"e164\":\"+17046180435\",\"label\":\"Test\",\"voice\":true,\"sms\":true}]"
}
```

Phone uses `voice: true` (default true). Messages will later use `sms`.  
Missing, blank, `[]`, or invalid JSON → emergency only.  
Plug-in: `CallAllowlist.allowedNumbers()`.

Do **not** put kid numbers in KSP / Backbone.

## Default Phone app

The APK cannot silently become the default dialer. Android 10+ `ROLE_DIALER` is a user or device-owner grant.

Dev-only on a test unit:

```
adb shell cmd role add-role-holder android.app.role.DIALER co.phonly.phone
```

On V2-DEV, Settings is SHOW: **Settings → Apps → Choose default apps → Phone app → Phone**.  
Fleet: Esper/MDM default-dialer if they expose it, or a one-time prompt the blueprint allows. Do not fake a silent in-app switch.

Sideload/debug vs Esper-signed: different certificates. Esper blocks `adb uninstall` (`DELETE_FAILED_USER_RESTRICTED`) until V2-DEV allows app uninstallation and Phone is off the blueprint. Then uninstall on the device, restore the library APK, CONVERGE.

## Visual voicemail

No VVM inbox in this APK. Leave Samsung/T-Mobile Visual Voicemail SHOW if that is the OEM/carrier client. Playback of **allowlisted** messages still needs a dedicated test. Dialpad long-press 1 is Fossify speed dial, not voicemail.

## Practical build notes

- `JAVA_HOME` = Android Studio `jbr`, `ANDROID_HOME` = `%LOCALAPPDATA%\Android\Sdk`.
- Debug: `.\gradlew.bat assembleFossDebug` → `%USERPROFILE%\AppData\Local\phonly-phone-build\app\outputs\apk\foss\debug\`.
- Release (Esper): `.\gradlew.bat assembleFossRelease` → `...\foss\release\phone-24-foss-release.apk`. Signing: `%LOCALAPPDATA%\phonly-phone-signing\` (not git, not Dropbox). Do not minify. Upload helper in Phonly Code: `esper-phone-upload-v2-dev.mjs`.
- Build output is **outside Dropbox**. Do not sync `app/build`, `build`, `.gradle` in Dropbox.

## Suggested next

1. Confirm AAAAY is on **signed** 1.1.0 / 24 (not leftover debug) and default Phone is set. Optional lab: reboot with a still-unacked miss; Home↔School. Then copy V2 Phone onto live Home **only if asked**.
2. Hide Samsung Dialer on live Home only after default Phone is set by policy or setup.
3. T-Mobile VVM playback for allowlisted callers.
4. PhonlyMessages (`co.phonly.messages`) — sibling folder `..\Phonly Messages`. Work happens there, not here.

Do not mix Esper V1=V2 cutover, PhonlyV1 codebase, or Knox Manage into this folder unless asked.

## Missed-call overlay (research 2026-08-28)

**Product:** one small **Missed call** bar on top of whatever is on screen (Home, another app) until the kid taps it or opens Recents. Allowlisted misses only. `IncomingAllowlistDrop` stays silent. Not a 2-second heads-up. Kids have no Settings and no notification shade.

Messages shipped unread **bubbles** (`co.phonly.messages` 1.1.0). Phone stays a top bar so the two do not fight. Do not put SMS here.

**Look**

Rounded bar near the top, below the status icons. One row tall. Not full-screen. Not a chat-head bubble. Stays until they deal with it (unlike an iPhone banner that drops into Notification Center).

Left to right: round photo if we have one, else the Phone icon; two lines — **Missed call** then the name (larger). Optional single **Call** control; otherwise tap the bar. Enough contrast on the wallpaper.

**Many misses — one bar, never a stack**

- Same person, several misses: update in place (“Mom, 3 missed calls”).
- Different people: still one bar. Latest caller plus “and 1 other” (or a count).
- Tap opens Recents. Opening Recents clears the bar. Call-back of the latest is optional, not a second bar.
- Do not pile cards. Do not one-chip-per-caller (covers Home; fights the unread-text bar).

**This APK today (1.1.0 / 24)**

- `SYSTEM_ALERT_WINDOW` + `MissedCallOverlay` top bar. Allowlisted misses only. `IncomingAllowlistDrop` stays silent. Declined calls do not show a bar.
- Fossify overlay snackbar / `ACTION_MANAGE_OVERLAY_PERMISSION` sends the kid to Settings. Dead on live Home. **Do not** use that for this feature.
- Incoming/in-call notifications (`CallNotificationManager`) are live-call UI, not this missed-call card.

**Grant (done 2026-08-30, operator):** fleet KSP Permission Controls Appear on top → `co.phonly.phone` and `co.phonly.messages`. Blueprint cannot grant this. Keep it on the fleet KSP profile. Home overlay proven on AAAAY (debug). Reboot-with-unacked-miss and Home↔School still optional.

**Lab prove (done on Home; V2-DEV, not live Home3)**

Grant ≠ draw over Esper Home. Proven: `TYPE_APPLICATION_OVERLAY` bar over Esper Home on AAAAY. Optional leftover: reboot re-show, Home↔School. If a later Home CONVERGE eats it, stop.

Samsung Floating notifications / Smart pop-up / chat bubbles need Settings. Not a path. Not a substitute.
