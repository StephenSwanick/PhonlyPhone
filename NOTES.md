# Phonly Phone — continuation notes

Public repo: https://github.com/StephenSwanick/PhonlyPhone (GPL-3).  
`origin` = PhonlyPhone. `upstream` = FossifyOrg/Phone.  
`main` = working branch. `clone` = frozen Fossify at `862bcfc8` — do not commit on `clone`.

This app stays its own public repo. Not inside PhonlyV1 / Phonly Code.  
Kotlin namespace stays `org.fossify.phone`. Install id is `org.phonly.phone` (debug: `org.phonly.phone.debug`).  
Messages / SMS is a later repo (PhonlyMessages). Do not put SMS here.

## What already works (tested on Galaxy A16 debug APK)

- Identity: launcher **Phone_debug** (debug string), About name Phonly Phone, NOTICE/GPL-3 kept.
- Hardcoded allowlist: `7046180435`, `7047185661`, plus 911 / Android emergency numbers.
- Outgoing: other numbers do not place a call; toast **Call not allowed**.
- Incoming (when this app is the **default Phone app**): non-allowlisted callers are rejected by `CallScreeningService`. **Confirmed on T-Mobile:** those callers can still **leave a voicemail**. Reject is not enough to prevent VM.
- Allowlisted inbound/outbound calls work.
- Fossify “fake version / Play Store” check rewritten at build time so `org.phonly.phone` is accepted.
- Outgoing intents target this app’s `DialerActivity`, not hardcoded `org.fossify.phone`.
- Default launcher icon is the Phonly brandmark (phone + shield). Fossify color-icon variants in Customize are still the old handset.
- Crash fix: keep `color_incoming_call` in `colors.xml` (removing it crashes recents after contacts load).

## Allowlist storage (agreed path)

- Spike: compile-time list in `CallAllowlist`.
- Long-term source of truth: **Mongo per device** on the Phonly backend — not a Mongo driver in this public APK.
- Delivery later: managed config, HTTPS API, or a local file; the dialer reads a **local working copy**.
- Plug-in point: `CallAllowlist.allowedNumbers()`. Incoming and outgoing already share `isNumberAllowed()`.

## Default Phone app

The APK **cannot** silently become the default dialer. Android 10+ `ROLE_DIALER` is a user or **device-owner** grant. Fossify already opens `RoleManager.createRequestRoleIntent`. Esper may swallow that UI.

Dev-only on a test unit (not for kid devices):

```
adb shell cmd role add-role-holder android.app.role.DIALER org.phonly.phone.debug
```

Product path: Esper/MDM default-dialer policy if they expose it, or a one-time setup prompt that is allowed on the blueprint. Do not fake a silent in-app switch.

## Visual voicemail

Phonly Phone / Fossify has **no VVM inbox**. Do not build one into this APK.

**Samsung Visual Voicemail** is a separate Galaxy app (typically `com.samsung.vvm`). Leave it SHOW in policy if that is the OEM client.

**T-Mobile Visual Voicemail** is on the test unit (separate carrier app). Still needs a playback test for **allowlisted** callers while this dialer is default. Blocked callers already reached carrier VM (see below).

Dialpad long-press 1 is Fossify **speed dial**, not voicemail. Carrier access (`*86`, own MSISDN) is blocked unless added to the allowlist.

## Preventing voicemail (next session: implement app path)

**Finding:** a not-allowed incoming call was able to leave VM. Screening **reject** is treated as decline; the carrier still offers voicemail.

**Product rule to implement next:** for incoming numbers that fail the allowlist, **answer and hang up immediately** (no ringing UI if we can suppress it) so the call is completed and most carriers skip VM. Keep the same allowlist. **Never** auto-drop 911 / emergency.

How: stop rejecting those calls in `CallScreeningService` (or only skip notification). In `CallService.onCallAdded`, if incoming and `!isNumberAllowed`, answer then disconnect. Expect a ~1s answered call on the caller’s bill; re-test on T-Mobile. Allowlisted inbound must still ring.

Other options (not chosen as the next code change):

1. **Carrier:** turn voicemail off on that T-Mobile line (no VM box at all).
2. Silence without reject — usually still hits VM after no-answer timeout. No API to delete a carrier greeting.

## Practical build notes

- Android Studio Quail 3 + SDK on this PC. `JAVA_HOME` = Studio `jbr`, `ANDROID_HOME` = `%LOCALAPPDATA%\Android\Sdk`.
- Debug APK: `.\gradlew.bat assembleFossDebug` → `app/build/outputs/apk/foss/debug/phone-22-foss-debug.apk`.
- Do not sync `app/build`, `build`, `.gradle` in Dropbox. Do not “Move out of Dropbox” on `.class` files (Kotlin/Gradle vs Dropbox).
- Launcher icon cache on Samsung may need clear cache / remove+add shortcut.

## Spike list (original order)

1. Identity — done.
2. Allowlist gate — done (hardcoded).
3. Debug APK / sideload — done (`org.phonly.phone.debug`).
4. Troubleshooter / V2-DEV Blueprint — in progress elsewhere (not this repo). Hide Samsung Dialer is **after** calls through this app work.

## Suggested next (when resuming)

1. **Implement incoming answer-and-drop** for not-allowed numbers (see above). Rebuild debug APK, sideload, confirm blocked callers cannot leave VM and allowlisted still ring.
2. T-Mobile VVM app: playback test for allowlisted messages.
3. Second allowlisted number in/out. Esper default-dialer remains a blueprint problem (no silent in-app switch).
4. Debug launcher label is still `Phone_debug` — optional rename to Phonly Phone.
5. Hide Samsung Dialer only after default-dialer + inbound/outbound + VM behavior are reliable.
6. Release `org.phonly.phone` (no `.debug`), signing, then per-device allowlist reader (no Mongo in the APK).
7. PhonlyMessages as a second public repo.

Do not mix Esper V1=V2 cutover, PhonlyV1 codebase, or Knox Manage into this folder unless asked.
