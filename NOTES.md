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
- Incoming (when this app is the **default Phone app**): non-allowlisted callers are rejected by `CallScreeningService`. On T-Mobile that went to **carrier voicemail**.
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

Phonly Phone / Fossify has **no VVM inbox**. Samsung Visual Voicemail is a **separate** app (typically `com.samsung.vvm`). Leave it SHOW in policy and test beside this dialer; do not build cassette-tape VM into this APK.

Dialpad long-press 1 is Fossify **speed dial**, not voicemail. Carrier access (`*86`, own MSISDN) is blocked unless added to the allowlist.

## Preventing voicemail (not implemented — pick later)

Rejected calls go to carrier VM because screening **rejects** (network treats it as decline).

1. **Carrier:** turn voicemail off on that T-Mobile line. Reject becomes busy/dead air. Best if the product wants no VM box at all.
2. **App:** do not reject in `CallScreeningService`. In `CallService`, for a disallowed incoming call, **answer and hang up immediately** (no UI). Most carriers then skip VM. Side effect: a 1-second answered call may bill the caller. Never auto-drop emergency numbers.
3. Silence without reject still usually hits VM after the no-answer timer. There is no API to delete the carrier VM greeting.

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

1. More testing: second allowlisted number, 911 only with a safe plan, Samsung VVM app if policy shows it, Dialpad vs recents vs contacts outgoing.
2. Esper: default dialer + SHOW `com.samsung.vvm`; clone blueprint for experiments, not production Troubleshooter.
3. Debug launcher label is still `Phone_debug` — optional rename to Phonly Phone.
4. Incoming answer-and-drop vs carrier VM off — product choice, then implement one.
5. Hide Samsung Dialer only after default-dialer + inbound/outbound are reliable.
6. Release `org.phonly.phone` (no `.debug`), signing, then per-device allowlist reader (no Mongo in the APK).
7. PhonlyMessages as a second public repo.

Do not mix Esper V1=V2 cutover, PhonlyV1 codebase, or Knox Manage into this folder unless asked.
