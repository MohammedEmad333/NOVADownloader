# NOVA 2.0.0 release readiness

## Verified by the pull request build
- Run Android JVM/Robolectric unit tests on the 2.0.0 source.
- Assemble the debug and unsigned release APKs.
- Verify the unsigned release package remains `com.example.ytdl`.
- Generate `SHA256SUMS.txt` for the debug APK artifact.
- Run privacy/regression tests for backup opt-out, exported components,
  cleartext network policy, and large Library snapshots.

The **debug APK is for testing**. The unsigned release APK is not signed or
installable. Neither can be described as a production-signed artifact.

## Required real-device acceptance (not verified by GitHub Actions)
1. Install the app on a supported Android device (min SDK 29). For an
   in-place upgrade, use the **same signing key** as the installed app;
   changing keys can require a reinstall and can lose private app state.
2. On a local video, test playback, seek timeline, resume position, 0.75x–2x
   speed, landscape, system back, and picture-in-picture start/exit.
3. On a local audio file, test headphones unplug, audio focus loss,
   background playback, lock-screen playback controls, and stop.
4. Start a public, permitted download. Pause/resume, switch between Wi-Fi and
   cellular, disconnect/reconnect, check notification behavior and integrity
   of the saved MediaStore item. Test a full storage/cache scenario.
5. Confirm Library list/grid, source filtering, favorites, custom collections,
   file availability messages, and non-destructive duplicate review.
6. Confirm Arabic RTL and English text on a narrow phone and larger display.
7. Verify data remains after a normal in-place update, then test that device
   migration does **not** restore stale queue/collection references.
8. Validate the exact release-signed APK on a physical device before any
   public announcement.

## Production signing (maintainer action)
1. Securely generate and store a long-lived Android signing keystore **outside**
   source control; use the same certificate for future in-place updates.
2. Configure the four GitHub Actions repository secrets:
   `NOVA_KEYSTORE_BASE64`, `NOVA_KEYSTORE_PASSWORD`,
   `NOVA_KEY_ALIAS`, `NOVA_KEY_PASSWORD`.
3. Manually dispatch **Signed release APK (manual)** from the **main** branch
   after main CI is green. It runs tests, assembles release, ZIP-aligns the
   unsigned APK, signs it and verifies signing certificates.
4. Download the `nova-release-signed-apk` artifact from that workflow.
   Validate its `SHA256SUMS.txt`, installation, certificate identity and
   media/notification behavior on-device.
5. Create a public version tag or release *only after* these manual gates pass.
   The workflow intentionally does not create GitHub Releases or publish to
   any app store.

## Privacy and data integrity
- NOVA's internal queue, Library labels and playback progress are local,
  device-specific records. They are excluded from automatic cloud backups
  and device-to-device transfers, and no login is required.
- Completed media saved in Android MediaStore is separate from app-private
  metadata; no user media is deleted by this release.
- Stored signing credentials must never appear in the repository, commits,
  logs or unsigned/test APK artifacts.
- App-private backup opt-out is an application configuration; verify OEM
  behavior on representative Android devices.

## Known limitations
- Media decoding, background activity limits and PiP availability depend on
  the device/Android build.
- Public external sources may change or become temporarily unavailable.
- No Play Store deployment, signing secrets or real-device results are
  included in the automated CI workflow.
