# Android Auto — Known Caveats

Notes on the Android Auto (`auto/`) implementation. See commit `073515e`
("fix(auto): repair Android Auto discovery and release host validation")
for the fixes these caveats relate to.

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
