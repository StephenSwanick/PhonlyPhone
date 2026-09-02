# Phonly Phone — continuation notes

Public repo: https://github.com/StephenSwanick/PhonlyPhone (GPL-3).  
`origin` = PhonlyPhone. `upstream` = FossifyOrg/Phone.  
`main` = working branch. `clone` = frozen Fossify at `862bcfc8` — do not commit on `clone`.  
`DialerBackup` = pre-AppConfig spike (hardcoded numbers + screening reject). Do not commit on `clone`.

This app stays its own public repo. Not inside PhonlyV1 / Phonly Code.  
Kotlin namespace stays `org.fossify.phone`. Install id is `co.phonly.phone` (debug and release; no `.debug` suffix).  
Messages / SMS is a later repo (PhonlyMessages, `co.phonly.messages`). Do not put SMS here.

## Status (2026-09-01) — cue/notify-device

Missed-call **overlay is dead** (CONVERGE kills `SYSTEM_ALERT_WINDOW`). Live cue is a delayed Esper `NOTIFY_DEVICE`. PhonlyAPI owns the timer. This APK reports facts only.

**Branch:** `cue/notify-device` from signed **1.0.0 / 23**. Debug sideload cannot POST on AAAAY (`UnknownHostException` for PhonlyAPI — Knox blocks unmanaged uid). Signed **1.2.0 / 31** is on AAAAY (Esper library SHOW **PhonlyV2 - DEV v36.0**, installer DPC, lastUpdate 22:43). Package `co.phonly.phone`. IMEI is **not** baked into release (AppConfig `device_imei` + telephony). Token from local `notification.properties` at assemble, not git.

**Lab (AAAAY, 2026-09-01) — missed-call pile proven.** Same Mongo `notification` as Messages. Recents open = looked; Home = left; swipe-kill not required; Close on the Esper card is not looked. Do not fleet CONVERGE. Do not Save KSP.

- **Messages SMS** (separate repo, `co.phonly.messages` **1.2.0 / 29** then **30**): unread while Messages closed → card ~60s later. Home is left. Extra texts after Close stay quiet.
- **Phone 30 hole:** skipped `POST /pile` when Android’s default-network “validated” flag was off (often during/after a voice call — **not** a real 2-minute outage), then quit at 2 minutes. 7:39 VM and 10:08 miss died that way. 10:00 POST landed but `alreadySent` from the 8:40 leftover card blocked a new wait (Recents had not LOOKED).
- **Phone 31:** POSTs anyway. First attempt 8s after the miss; on failure retry every 15s until Recents is opened or the POST succeeds (no 2-minute give-up).
- **Prove (22:48 ET):** allowlisted caller rang AAAAY and **the calling phone hung up** (unanswered). Recents `MISSED` (`type=3`). 31 `telecom miss disconnect=5`, first pile `UnknownHostException`, retries continued, Esper card **22:52**. Caller hang-up / ring-out **is** a miss; it does **not** have to reach VM.
- **LOOKED/CLEAR:** Recents open 22:46 unlocked the CONVERGE leftover wait (`sentAt` 22:45); Home → CLEAR. Default Phone must be set again after CONVERGE (`ROLE_DIALER` often reverts to Samsung).

**Not this repo:** Messages **30** still has the validated-network skip + 2-minute give-up. Fix in the Messages agent. Do not edit Messages from here.

**Debug cert:** `%USERPROFILE%\.android\debug.keystore` (same as Messages, for `co.phonly.permission.NOTIFICATION`). Signed uninstall first; debug cannot overwrite the Esper cert.

**This APK:** no overlay draw, no Appear-on-top checks, no AlarmManager for the 15-minute wait, no Esper key, no caller id on the wire. **Callee** decline / hang-up while ringing is **not** a miss (`REJECTED`). **Caller** hang-up / ring-out **is** (Recents `MISSED` or `VOICEMAIL`, even if the carrier sets connect time). Powered-off call that went to carrier VM is **not** a miss (`CallService` never ran).

**Facts:** Recents missed or voicemail row (allowlisted, ring-out / caller hang-up / VM) while Recents is not in front → `POST /pile` (debounced 8s; retry every 15s until LOOKED or POST; do not skip on Android’s validated-network flag). Callee hang-up is not a miss. Do not skip pile on local `piledThisCycle` / `alreadySent` / `waitingSince`; extra POSTs are harmless (API ignores if already waiting or `alreadySent`). Recents open → `POST /looked`. Recents leave / Home is “left” (swipe-kill not required): remaining unacked → `POST /pile`; after we acked our miss pile → `POST /clear`. Boot GET must not overwrite a LOOKED that landed while GET was in flight. LOOKED/LEFT broadcasts to Messages are best-effort only (different release certs; Phone owns `co.phonly.permission.NOTIFICATION`). Do not assume they arrive. Close on the Esper card is not looked.

**Token / IMEI:** lab Bearer from env or `%LOCALAPPDATA%\phonly-phone-signing\notification.properties` (`token=`). Not in git. Release forces `DEVICE_IMEI_OVERRIDE=""`. Runtime IMEI: AppConfig `device_imei`, then cache, then telephony. Empty IMEI → log `WOULD_PILE skip: no IMEI`.

**Do not:** treat overlay 1.1.0 as the live path. Do not put numbers in the JSON body. Do not bake AAAAY IMEI into the signed library APK.

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
- Release (Esper): `.\gradlew.bat assembleFossRelease` → `...\foss\release\phone-31-foss-release.apk`. Signing: `%LOCALAPPDATA%\phonly-phone-signing\` (not git, not Dropbox). First Esper cut is **not** minified.
- Build output is **outside Dropbox**. Do not sync `app/build`, `build`, `.gradle` in Dropbox.

## Suggested next

1. Messages agent: drop the validated-network skip and 2-minute give-up (same as Phone 31). Do not edit Messages from this repo.
2. Optional lab: callee decline on AAAAY should **not** card (`REJECTED`). Caller hang-up already proven 22:48→22:52.
3. Hide Samsung Dialer on live Home only after default Phone is set by policy or setup. Do not fleet CONVERGE. Do not Save KSP.

Do not mix Esper V1=V2 cutover, PhonlyV1 codebase, or Knox Manage into this folder unless asked.
