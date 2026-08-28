# Phonly Phone — continuation notes

Public repo: https://github.com/StephenSwanick/PhonlyPhone (GPL-3).  
`origin` = PhonlyPhone. `upstream` = FossifyOrg/Phone.  
`main` = working branch. `clone` = frozen Fossify at `862bcfc8` — do not commit on `clone`.  
`DialerBackup` = pre-AppConfig spike (hardcoded numbers + screening reject). Do not commit on `clone`.

This app stays its own public repo. Not inside PhonlyV1 / Phonly Code.  
Kotlin namespace stays `org.fossify.phone`. Install id is `co.phonly.phone` (debug and release; no `.debug` suffix).  
Messages / SMS is a later repo (PhonlyMessages, `co.phonly.messages`). Do not put SMS here.

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
- Release (Esper): `.\gradlew.bat assembleFossRelease` → `...\foss\release\phone-23-foss-release.apk`. Signing: `%LOCALAPPDATA%\phonly-phone-signing\` (not git, not Dropbox). First Esper cut is **not** minified.
- Build output is **outside Dropbox**. Do not sync `app/build`, `build`, `.gradle` in Dropbox.

## Suggested next

1. **Next session:** missed-call overlay in this APK, sideload to a lab unit. See below. Do not Esper-sign, library-upload, or CONVERGE unless asked.
2. Hide Samsung Dialer on live Home only after default Phone is set by policy or setup.
3. T-Mobile VVM playback for allowlisted callers.
4. PhonlyMessages (`co.phonly.messages`) — sibling folder `..\Phonly Messages`, frozen Fossify at `25971576`. Work happens there, not here.
5. Copy V2-DEV Phone posture onto Home when boring. Not this commit.

Do not mix Esper V1=V2 cutover, PhonlyV1 codebase, or Knox Manage into this folder unless asked.

## Missed-call overlay (research 2026-08-28)

**Product:** one small **Missed call** bar on top of whatever is on screen (Home, another app) until the kid taps it or opens Recents. Allowlisted misses only. `IncomingAllowlistDrop` stays silent. Not a 2-second heads-up. Kids have no Settings and no notification shade.

Messages wants the same kind of bar for unread texts (`co.phonly.messages`). Do not put SMS here; keep the overlay grant and lab-prove story aligned. One pattern for both apps.

**Look**

Rounded bar near the top, below the status icons. One row tall. Not full-screen. Not a chat-head bubble. Stays until they deal with it (unlike an iPhone banner that drops into Notification Center).

Left to right: round photo if we have one, else the Phone icon; two lines — **Missed call** then the name (larger). Optional single **Call** control; otherwise tap the bar. Enough contrast on the wallpaper.

**Many misses — one bar, never a stack**

- Same person, several misses: update in place (“Mom, 3 missed calls”).
- Different people: still one bar. Latest caller plus “and 1 other” (or a count).
- Tap opens Recents. Opening Recents clears the bar. Call-back of the latest is optional, not a second bar.
- Do not pile cards. Do not one-chip-per-caller (covers Home; fights the unread-text bar).

**This APK today**

- Declares `SYSTEM_ALERT_WINDOW`. Does **not** post its own missed-call banner. After a miss, the system Telecom notifier is shade UI; we only `cancelMissedCallsNotification()` when Recents is opened.
- Fossify overlay snackbar / `ACTION_MANAGE_OVERLAY_PERMISSION` sends the kid to Settings. Dead on live Home. **Do not** use that for this feature.
- Incoming/in-call notifications already exist (`CallNotificationManager`). That is live-call UI, not a missed-call card.

**Grant (not this repo; nothing granted yet)**

- Esper blueprint / console / `SET_APP_PERMISSION` cannot grant Display over other apps. That API is runtime only (camera, mic, `POST_NOTIFICATIONS`, …). Overlay is AppOps.
- Silent A16 path: Esper **Knox Service Plugin** iframe (same Play Store MCM as fleet RCS / battery — not a blueprint Save, not slim `UPDATE_DEVICE_CONFIG` JSON). Device-wide + application management controls are already on. Turn on **Enable permission controls**. Permission Controls row: policy **Appear on top**, package `co.phonly.phone`. Later a second row for `co.phonly.messages`.
- KSP 24.03+, Android 13+, fully managed; no user prompt; re-applied when the package is installed. A16 qualifies. A14 / One UI Core does not (KPE 90010).
- Survives reboot and Home↔School CONVERGE if it stays on the **fleet** KSP profile. Overwriting that iframe can remove it. Publishing Home3 does not create it.
- Do not embed Esper Device SDK `setAppOpMode` in this GPL repo.
- Backup ongoing notification needs an explicit Esper runtime grant of `POST_NOTIFICATIONS` (blueprint Allow Automatically, or `SET_APP_PERMISSION` on this package). `ROLE_DIALER` / CallStyle does **not** cover a generic ongoing notification. KSP “Notification access” is NotificationListener — wrong. Do not use KSP notification allowlist (blocks everyone else). If the blueprint is Ask User, the kid cannot tap Allow.

**Lab prove (ship-blocker; V2-DEV / Troubleshooter, not live Home3)**

Grant ≠ draw over Esper Home. Units are Esper Launcher multi-app, not Phone pinned as single-app kiosk. If Appear on top is granted and the card is a real `TYPE_APPLICATION_OVERLAY` window, it should show; some lock-task launchers still hide third-party overlays. Prove: card over Home, after reboot, after Home↔School. If Home eats it, the floating card cannot ship.

Samsung Floating notifications / Smart pop-up / chat bubbles need Settings. Not a path. Not a substitute.

**Next session (this repo)**

Sideload a variant of Phonly Phone. Debug and this signed release **cannot** overwrite each other (see Status). Keep package `co.phonly.phone`. Do not live-Home CONVERGE.

- On allowlisted incoming disconnect as missed (not reject, not allowlist drop): show or update the single overlay bar (see Look / Many misses).
- Tap → Recents. Opening Recents clears it. No swipe-to-dismiss that leaves the miss with nothing on screen.
- If overlay AppOps is missing: **do not** open Settings. Ongoing notification only.
- Re-show after process death / boot if still unacknowledged.
- Pair with `setOngoing(true)` notification; cancel with the bar.
- Fallback if overlay cannot sit on Home: status-bar mark only. Weak (fleet notification volume is 0; no shade). Not the product.
