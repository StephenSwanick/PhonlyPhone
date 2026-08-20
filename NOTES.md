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

1. Hide Samsung Dialer on live Home only after default Phone is set by policy or setup.
2. T-Mobile VVM playback for allowlisted callers.
3. PhonlyMessages (`co.phonly.messages`) reading the same `allowlist_json` (`sms: true`).
4. Copy V2-DEV Phone posture onto Home when boring. Not this commit.

Do not mix Esper V1=V2 cutover, PhonlyV1 codebase, or Knox Manage into this folder unless asked.
