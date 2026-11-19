# NOVA 2.3.0 release and publication checklist

## Release identity

- Application ID: `com.example.ytdl`
- Version name: `2.3.0`
- Version code: `33`
- Minimum Android: 10 (API 29)
- Target SDK: 36
- Official download page: https://github.com/MohammedEmad333/NOVADownloader/releases

## Automated build

The pull-request and main workflows run Android JVM/Robolectric tests, assemble
Debug and unsigned Release APKs, verify the application identity, and upload a
Debug APK artifact. These checks do not create a production-signed APK.

The manual **Signed release APK (manual)** workflow runs only from `main`.
It requires the maintainer-owned Actions secrets:

- `NOVA_KEYSTORE_BASE64`
- `NOVA_KEYSTORE_PASSWORD`
- `NOVA_KEY_ALIAS`
- `NOVA_KEY_PASSWORD`

The workflow verifies the APK signature and application identity, then creates
a **draft GitHub Release** tagged `v2.3.0` with these assets:

- `nova-release-signed.apk`
- `SHA256SUMS.txt`
- `SIGNING_CERT_SHA256.txt`

The draft is not visible as a published release to the public. It must remain
a draft until the exact signed APK passes the manual checks below.

## Required maintainer acceptance before publishing

- [ ] Confirm the signed certificate SHA-256 matches the trusted existing
      signing certificate used by any installed NOVA version that must upgrade
      in place. Never rotate the key silently.
- [ ] Verify the APK checksum against `SHA256SUMS.txt`.
- [ ] Install the exact signed APK on Android 10+ physical devices.
- [ ] Verify in-place upgrade and retained app data where applicable.
- [ ] Test video playback, seeking, resume position, speed, orientation and PiP.
- [ ] Test background audio, lock-screen controls, explicit pause/stop,
      transient/permanent audio focus loss and headset removal.
- [ ] Test download pause/resume, Wi-Fi/cellular changes, disconnect/reconnect,
      notifications and saved-file integrity.
- [ ] Test Library list/grid, search, source filters, favorites, collections,
      file availability and non-destructive duplicate review.
- [ ] Test Arabic RTL and English layouts on narrow and larger screens.
- [ ] Record device model, Android version and test outcomes without personal
      data or signing credentials.

## Publish

Only after every required check passes, open the draft at:

https://github.com/MohammedEmad333/NOVADownloader/releases

Review the assets and release notes, then use GitHub's **Publish release**
action. The release will then be public and downloadable without a GitHub
account. Do not publish a Debug or unsigned APK.

## Current limitation

GitHub Actions cannot establish real-device acceptance or prove that repository
signing secrets contain the previously trusted production key. Those checks
must be completed by the maintainer. No public release is claimed by this
document or by a successful CI run.
