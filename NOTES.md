# Phonly Phone — continuation notes

Public repo: https://github.com/StephenSwanick/PhonlyPhone (GPL-3).  
`origin` = PhonlyPhone. `upstream` = FossifyOrg/Phone.  
`main` = working branch. `clone` = frozen Fossify at `862bcfc8` — do not commit on `clone`.  
`DialerBackup` = pre-AppConfig spike (hardcoded numbers + screening reject). Do not commit on `clone`.

This app stays its own public repo. Not inside PhonlyV1 / Phonly Code.  
Kotlin namespace stays `org.fossify.phone`. Install id is `co.phonly.phone` (debug and release; no `.debug` suffix).  
Messages / SMS is a later repo (PhonlyMessages, `co.phonly.messages`). Do not put SMS here.

## Status (2026-09-09) — Phone 36 on AAAAY (V2-DEV)

**1.4.0 / 36** is in the Esper library and pinned SHOW on **PhonlyV2 - DEV v44.0**. Operator CONVERGEd AAAAY: Phone, Messages, and `co.phonly.contacts` are all on that unit. Live Home/School/TS stay on **34**. Do not copy 36 onto those blueprints until asked (APK replace drops default Phone).

This cut: Esper boolean **`allowlist_enabled`** next to **`allowlist_json`** (only explicit `false` allows all calls; missing or `true` = today’s empty-list = emergency only). Kid cannot create/edit/delete Android contacts (`canEditDeviceContacts()`). Names still come from Contacts Provider. This APK does **not** write that DB and does **not** read `label` from `allowlist_json`. Tap calls.

**Lab leftover (not this APK):** **Send to phone** still pushes Phone + Messages only. 2026-09-09 ~11:43Z AAAAY AppConfig for those two packages matched Mongo (Stephen / Andrea / Erik / Christina / Catherine, `allowlist_enabled: true`). `co.phonly.contacts` got no AppConfig, so names in Phone did not change. Third Esper target is PhonlyV1. Testing continues elsewhere.

**1.3.0 / 35** was the local `allowlist_enabled` cut; **36** superseded it before library upload. Do not pin 35.

## Status (2026-09-02) — Phone 34 on AAAAY (cardStatus)

Missed-call **overlay is dead**. Live cue is Esper `NOTIFY_DEVICE`. PhonlyAPI owns the timer. Missed-call and unread-text are **two independent slots**. This APK talks HTTP itself (`source=phone`). Do not funnel through Messages. Do not fleet CONVERGE. Do not Save KSP.

PhonlyAPI notification JSON is **`cardStatus` idle|waiting|shown**, `reminderStartedAt`, `cardShownAt` only. Do not parse or write `alreadySent` / `waitingSince` / `sentAt`. HTTP is still GET `/api/device/notification?imei=&source=phone` and POST `/pile|looked|clear` body `{ imei, source: "phone" }`. Missing source → 400. Recents / Call history looked-or-clear may only send `source=phone`. Never `source=messages`. Recents is not read texts. Inbox LOOKED/LEFT from Messages is ignored. Slot is never deleted. Looked/clear → idle (`reminderStartedAt` null, `cardShownAt` kept). Quiet = not waiting. `shown` = card already sent; extra pile does not restart.

**On device:** signed **1.2.0 / 34** on AAAAY (operator CONVERGE after library **v43.0**). Package `co.phonly.phone`. IMEI is not baked in. Token from local `notification.properties` at assemble, not git. **33** remains in library history (DEV **v39.0**). **32** remains in library history (DEV **v38.0**). Do not copy Messages 34 hold-while-inbox-open.

**Recents = Call history tab in front.** On AAAAY, launching Phone lands on that tab (no Recents button). Recents open → `POST /looked` `source=phone` only. Home / leave Phone → LEFT: remaining unacked misses → `POST /pile`; after we acked our misses (`owedClear`) → `POST /clear`. A miss **while Call history is already in front** stays unacked until Home, then piles (accepted). Clear must not touch messages (API enforces). Inbox LOOKED/LEFT is ignored. Close on the Esper card is not looked. Card copy is API: title **Phonly**, body **You have a missed call.** Esper’s second Close-confirm is Esper chrome, not our payload. Extra misses while `phone.cardStatus` is `shown` stay quiet until Call history (API does not restart the wait). Boot GET: `shown` stays quiet; `waiting` means the reminder is already open; `idle` may pile if there are unacked misses and Recents is not in front. `knownSigner` / matching Messages cert is optional; cross-app LOOKED is not required.

**34 (this cut, proven):** parse/cache the three fields only (`DeviceNotificationApi` / `DeviceNotificationCue`). Same Recents product as 33. Same FGS / ranked POST as 33. Git `316cdc92` / `ece87755`. Library upload `phone-34-foss-release.apk` → PhonlyV2 - DEV **v43.0**. Operator CONVERGE + test on AAAAY succeeded 2026-09-02 (~15:28).

**33 (history):** after a miss, start a short foreground service through the 8s wait and POST (Android 14 `shortService`, ~3 min max). Stop on LOOKED or a successful pile. Rank WiFi / ethernet / cell via `Network.openConnection`; do not `bindProcessToNetwork`. Not Recents. Not overlay. Not AlarmManager. Not Messages. Operator did not see a shade flash; leave the FGS as-is. Parsed the old JSON names.

**AAAAY proof (33) — 2026-09-02 afternoon.** Phone pid **12327**. Leave Call history closed unless testing LOOKED.

- 11:33 CONVERGE leftover: `post FGS up` → PILE `via=wifi/ok HTTP 200` `waitingSince=2026-09-02T15:32:16.853Z`. Card `sentAt=2026-09-02T15:34:03.834Z`.
- 11:49:58 miss (Call history closed): FGS + debounce kept → 11:50:07 PILE `waitingSince=2026-09-02T15:50:07.011Z`. Card `sentAt=2026-09-02T15:52:04.801Z`. Messages slot unchanged.
- Opening Phone LOOKED then CLEAR: `phone.alreadySent=false`, `waitingSince=null`, `sentAt` kept. Messages untouched.
- 11:57 miss with Call history still open: `miss while Recents open; wait for LEFT` (no pile until Home).
- After Home + 12:01 extra miss: 12:00:44 PILE `waitingSince=2026-09-02T16:00:44.928Z`; 12:01 extra POST reused the same wait (debounce kept). Card 12:02. Mongo: `phone.alreadySent=true`, `waitingSince=16:00:44.928Z`, `sentAt=2026-09-02T16:02:03.987Z`. Messages: `alreadySent=false`, `waitingSince=null`, `sentAt=2026-09-02T13:56:13.858Z`.

**32 (history):** call-log and network ticks must not reset the 8s/15s wait. Do not skip pile on VALIDATED. Lab 10:22: WiFi ranked but still `UnknownHostException` until 33’s FGS. 9:38 (APK 31) never reached Mongo (leftover call path + debounce reset + DNS stampede).

**Lab (AAAAY, 2026-09-01) — missed-call pile proven on 31** when DNS worked. Caller hang-up / ring-out **is** a miss; it does not have to reach VM. Phone 30 skipped pile on Android’s “validated” flag during/after a voice call, then quit at 2 minutes — 31 POSTs anyway and retries every 15s until Recents or POST.

**This APK:** no overlay draw, no AlarmManager for the wait, no Esper key, no caller id on the wire. **Callee** decline / hang-up while ringing is **not** a miss (`REJECTED`). **Caller** hang-up / ring-out **is** (Recents `MISSED` or `VOICEMAIL`). Powered-off call that went to carrier VM is **not** a miss (`CallService` never ran).

**Facts:** Recents missed or voicemail row (allowlisted) while Call history is not in front → `POST /pile` (debounced 8s, not reset on observer ticks; retry every 15s until LOOKED or POST; do not skip VALIDATED). Recents open → `POST /looked`. Recents leave / Home: remaining unacked → pile; after we acked our miss → clear. Boot GET must not overwrite a LOOKED that landed while GET was in flight. Body `{ imei, source: "phone" }`. Missing `source` → 400.

**Token / IMEI:** lab Bearer from env or `%LOCALAPPDATA%\phonly-phone-signing\notification.properties` (`token=`). Not in git. Release forces `DEVICE_IMEI_OVERRIDE=""`. Runtime IMEI: AppConfig `device_imei`, then cache, then telephony. Empty IMEI → log `WOULD_PILE skip: no IMEI`.

**Do not:** treat overlay 1.1.0 as the live path. Do not put numbers in the JSON body. Do not bake AAAAY IMEI into the signed library APK. Do not edit Phonly Messages or PhonlyAPI from here.

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

- Source of truth: Mongo `phonly.devices.allowList` (Phonly Code). No Mongo in this APK.
- Delivery: Esper managed config on package **`co.phonly.phone`**: string **`allowlist_json`**, boolean **`allowlist_enabled`**.
- **`allowlist_enabled`:** only an explicit `false` (boolean, or strings `false` / `0`) means allow all. Missing key or `true` keeps today’s empty-list = emergency only. Do not treat a missing key as open — live kids stay locked until PhonlyAPI pushes `false`.
- Shape of `allowlist_json`: JSON **string** whose contents are an array:

```json
[{"e164":"+17046180435","label":"Test","voice":true,"sms":true}]
```

Esper’s device AppConfig box must be:

```json
{
  "allowlist_json": "[{\"e164\":\"+17046180435\",\"label\":\"Test\",\"voice\":true,\"sms\":true}]",
  "allowlist_enabled": true
}
```

Phone uses `voice: true` (default true). Messages will later use `sms`.  
When the list is **on**: missing, blank, `[]`, or invalid JSON → emergency only.  
When the list is **off** (`allowlist_enabled: false`): any number may be called; 911 is always allowed.  
Plug-in: `CallAllowlist`.

Do **not** put kid numbers in KSP / Backbone.

## Kid view-only contacts

- Names in Contacts / Recents / caller ID come from Android Contacts Provider. `co.phonly.contacts` writes that DB. This APK does **not** write Contacts Provider and does **not** read `label` from `allowlist_json`.
- Kid UI: view names, tap to call or text. Create / add-to-contact / delete / system contact editor are hidden (`canEditDeviceContacts()`).

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
- Release (Esper): `.\gradlew.bat assembleFossRelease` → `...\foss\release\phone-36-foss-release.apk`. Signing: `%LOCALAPPDATA%\phonly-phone-signing\` (not git, not Dropbox). First Esper cut is **not** minified.
- Build output is **outside Dropbox**. Do not sync `app/build`, `build`, `.gradle` in Dropbox.

## Suggested next

This Phone cut is closed. Testing is elsewhere. **Send to phone** must gain a third Esper target (`co.phonly.contacts`) in PhonlyV1 before names appear. Do not pin 36 over **34** on live Home/School/TS. Do not fleet CONVERGE. Do not Save KSP unless asked.

Do not mix Esper V1=V2 cutover, PhonlyV1 codebase, or Knox Manage into this folder unless asked.
