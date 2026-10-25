# Android Auto — Known Caveats

Notes on the Android Auto (`auto/`) implementation. See commit `073515e`
("fix(auto): repair Android Auto discovery and release host validation")
for the fixes sections 1–2 relate to.

## 1. Car app category: `IOT` is a pragmatic choice, not a perfect fit

The `CarAppService` intent filter declares
`androidx.car.app.category.IOT`, because Android Auto requires a category
for host discovery and IOT ("take relevant actions on connected devices
from the car") is the closest documented fit — the car UI acts on a
connected, self-hosted OpenCode server.

Be aware:

- It is not a genuine match. The app is a developer-tool/session browser,
  which is not one of Google's Android Auto categories (NAVIGATION, POI,
  IOT, WEATHER, MEDIA, MESSAGING, CALLING).
- Fine for sideloaded/development use and for DHU testing.
- **Google Play eligibility is doubtful regardless of category.** Google
  reviews car apps against the declared category's quality guidelines; a
  generic session browser is unlikely to pass as IOT. Treat Play
  distribution as a separate, unresolved question — do not assume the
  current category unlocks it.

## 2. Release `HostValidator` digest: verify before shipping

`app/src/main/res/values/arrays.xml` (`hosts_allowlist`) contains the
SHA-256 signing-certificate digest
`19:75:b2:f1:71:77:bc:89:a5:df:f3:1f:9e:64:a6:ca:e2:81:a5:3d:c1:d1:d5:9b:1d:14:7f:e1:c8:2a:fa:00`
for `com.google.android.projection.gearhead` (Android Auto phone
projection). This is Google's widely published release digest for
gearhead (as used in Google's official samples), but it was **not
re-verified against a current Android Auto APK** when added.

Be aware:

- **Before shipping a release build, verify the digest** against the
  Android Auto APK actually installed on your test device:
  `apksigner verify --print-certs` on the gearhead APK, and compare the
  SHA-256 certificate digest (colon-separated lowercase hex).
- If the digest mismatches, release builds will reject the Android Auto
  host — that is the validator working as designed. Update the entry in
  `arrays.xml`, keeping the documented `"<digest>,<package-name>"` format.
- Google may sign gearhead with more than one certificate (e.g. after
  key rotation). If connection failures appear only for some users/builds
  of Android Auto, add the additional digests as extra `<item>` entries.
- Debug builds are unaffected: they use
  `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` (see
  `OpenCodeCarAppService.createHostValidator()`), so this caveat only
  bites in release.

## 3. Real-car visibility for sideloaded installs: keep `<uses name="notification"/>`

`automotive_app_desc.xml` must declare BOTH `notification` and `template`.
This is not about messaging features — it is about launcher visibility on
real head units.

Per Google's
[Test Android apps for cars](https://developer.android.com/training/cars/testing)
(page updated 2026-10-06):

> Android Auto has a developer option that lets you run apps that aren't
> installed from a trusted source. This setting applies to **media,
> messaging notifications, and parked apps** but **doesn't apply to apps
> built using the Android for Cars App Library**.

Consequences, confirmed empirically on a real head unit:

- The "Unknown sources" developer setting does NOT surface a sideloaded
  pure-template app. Removing `<uses name="notification"/>` (done in
  `073515e`) hid the app entirely on a real car while the DHU kept
  showing it (the DHU is a dev tool and enforces nothing). Restored in
  0.4.1 — do not remove it again.
- With `notification` + `template` declared and the app sideloaded, the
  car surfaces only the notification entry-point. The template screens
  (projects/sessions menu) still do not appear on a real head unit;
  they only run in the DHU.

To get the FULL template UI (menu, readout, TTS) on a real car, the app
must be installed from a trusted source:

1. **Google Play Internal App Sharing** (documented, no review):
   one-time $25 Play Console account + identity verification, upload the
   signed build, install from the share link. The 12-tester closed-track
   rule applies only to production releases, not internal distribution.
   This is the only future-proof route; Google keeps tightening the
   sideload loopholes.
2. **Install-source spoofing** (free, fragile): reinstall with the
   installer field set to the Play Store —
   `adb shell pm install -r -i com.android.vending /data/local/tmp/app.apk`
   (verify under Settings → Apps → OpenCode: "App installed from Google
   Play Store"), or use KingInstaller (reliable on Android 11–13,
   partial on 14, limited on 15; Shizuku/root fallbacks). Must be
   re-applied after EVERY install/update — a plain `adb install` or
   Android Studio run resets the installer field.
3. Root + LSPosed modules (e.g. XLauncher Unlocked) for rooted devices.

## 4. Troubleshooting: app icon does not appear on a real head unit

The required configuration is already in place
(`automotive_app_desc.xml` declares both `notification` and `template`,
the manifest wires `com.google.android.gms.car.application` and the
`CarAppService` intent filter). If the icon still does not appear, work
through this checklist in order:

1. **Verify the installer spoof actually stuck.** `pm install -i` can
   report `Success` while silently ignoring the installer field,
   especially on newer Android versions. Check the real value:
   `adb shell pm list packages -i it.ferdiu.opencodewrapper`
   must print `installer=com.android.vending`. If it shows
   `installer=null` or `com.android.shell`, Android Auto treats the app
   as untrusted and hides it. Re-apply with:
   `adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app.apk`
   then
   `adb shell pm install -r -i com.android.vending /data/local/tmp/app.apk`.
   Confirmed working via adb shell on Android 16 (SDK 36); if your
   version ignores it, use KingInstaller (Shizuku) or root.
   **The spoof must be re-applied after EVERY install/update** — a plain
   `adb install` or Gradle `installDebug` resets the field to `null`.
2. **Android Auto developer settings (on the phone, not settable via
   adb):** enable developer mode (tap the version number ~10× in Android
   Auto settings), then enable **"Unknown sources"**. Also check
   **"Customize launcher"** — newly appearing apps can be hidden there
   by default.
3. **Clear Android Auto's cached app list** after changing any of the
   above: `adb shell am force-stop com.google.android.projection.gearhead`,
   then unplug/replug the phone.
4. **Release builds only — verify the HostValidator digest.** Debug
   builds allow all hosts (`FLAG_DEBUGGABLE` → `ALLOW_ALL_HOSTS_VALIDATOR`),
   so this cannot bite in debug. For release, compare the `hosts_allowlist`
   digest in `app/src/main/res/values/arrays.xml` against the installed
   gearhead APK's signing certificate (see section 2). A mismatch means
   the host handshake is rejected and the icon never appears.

Quick isolation test: install a debug build and check the DHU. If the
app appears in the DHU but not the real car, the code is fine and the
issue is purely install-source trust (steps 1–3). If it does not appear
even in the DHU, suspect manifest/discovery or the host validator
(step 4).
