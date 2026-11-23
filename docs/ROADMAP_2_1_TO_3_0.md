# NOVA Downloader: Roadmap 2.0.1 → 3.0.0

Status: **active development roadmap**; individual implemented slices are recorded in release notes, while device-only acceptance remains explicit.  
Baseline: NOVA 2.0.0, `main` at `07bfa3ba6f54f77d2032868a3dbcbe27fd5f2cb6`.  
Last reviewed: 2026-11-17.  
Scope: native Android/Java, minSdk 29, targetSdk 36, same app ID `com.example.ytdl`.

## Principles

1. **Protect users' data.** Never silently delete saved MediaStore items, completed downloads, favorites, collections or resumable temporary downloads.
2. **Fix regressions before features.** Android lifecycle, security/privacy and data-integrity defects block feature releases.
3. **Supported public/authorized media only.** Do not implement DRM, account, paywall or access-control bypasses, or fabricate support for unsupported external services.
4. **Local-first.** No accounts, cloud syncing, broad storage permissions or analytics SDK by default. If diagnostic logging is added, make it local, minimal and explicitly user-controlled.
5. **Small reviewable releases.** Each version gets a distinct branch, PR, focused tests, CI, debug APK and manual acceptance notes. Merge only when required checks succeed; rerun CI on `main`.
6. **Signing and publication remain separate.** A passing debug/unsigned build does not qualify as a public production release. Reuse the established signing certificate and require physical-device acceptance before publication.

## Current architecture and reasons for these priorities

- `DownloadService.java` (about 1,716 lines) owns queue persistence, Android foreground lifecycle, transfer orchestration, media publication and recovery. It targets API 36 and declares a `dataSync` foreground service, but does not override Android's modern `Service.onTimeout(int,int)`: **P0 review**. See [#77](https://github.com/MohammedEmad333/NOVADownloader/issues/77) and [Android documentation](https://developer.android.com/develop/background-work/services/fgs/timeout).
- `MainActivity.java` (about 2,032 lines), `HistoryActivity.java` (about 1,091 lines) and direct `BaseAdapter.getView` UI code suggest opportunities for smaller presentation components and recycled or paged lists, but a migration must be benchmarked rather than assumed faster.
- `AudioPlaybackService.java` uses Android `MediaPlayer`; current video playback already supports timeline, speeds and PiP. Migration to AndroidX Media3 is **conditional on a tested prototype**, not an unconditional rewrite.
- `LibraryCollections.java` uses a bounded, synchronized `SharedPreferences` JSON document. The model protects entry identity, but larger libraries may benefit from incremental database operations after measured profiling.
- `Net.java` already supports HTTP retry/resume safeguards. Prefer integration fixtures and integrity tests to speculative new sources.
- Existing JVM/Robolectric tests include a 6,000-record library stress test; release-quality frame, memory, power, migration and real-device measures are not yet established.

## Release sequence

### 2.0.1 — Critical lifecycle safety (P0, first)

Implementation tracked by [#77](https://github.com/MohammedEmad333/NOVADownloader/issues/77).
Automated regression validation does **not** replace the Android 15/16 physical-device timeout test or signed-release approval.

**Goal:** Address dataSync foreground service limits and stop/restart races *before* further feature additions.

- Audit `DownloadService` behavior on Android 15/16: `onTimeout(int,int)`, service stop, foreground eligibility, network callbacks, notifications, pause/cancel precedence and persisted queue snapshots.
- On timeout, stop promptly, preserve completed files and protected partial transfers, record a recoverable state, and invite user-initiated continuation without prohibited background FGS restarts.
- Add deterministic race/lifecycle tests; run Android 10–16 manual test matrix.
- **Acceptance:** timeout does not crash the app or silently discard resumable bytes; queued work remains correctly represented; all existing download tests remain green.

### 2.1.0 — Queue reliability and repair (P0/P1)

**Goal:** Make long downloads and app/process restarts predictable.

- Extract queue state transitions into a testable state machine; separate persistence and foreground-service orchestration.
- Improve resume validation for changed source URLs, 200/206/416 range responses, ETag, content length and corrupt partial bytes. Never append mismatched data.
- Surface actionable failure reasons with explicit Retry / Keep paused / Cancel choices; no unbounded automatic retries.
- Add network-fixture tests (drop connection, changing ETag, interrupted publish, low disk).
- **Acceptance:** no duplicate/mismatched output and no partial-file deletion on recoverable interruption; restart and resume tests pass.

### 2.2.0 — Library performance and usability (P1)

**Goal:** Keep scrolling, search and saved organization smooth on large collections.

- Benchmark startup, library opening, search latency, scrolling and memory on representative low/midrange devices; define baseline and target improvement after measurement.
- Extract Library UI/adapters from monolithic Activity; reduce full list rebuilds and expensive repeated binding.
- Consider RecyclerView and Room/SQLite for indexing collection memberships if benchmarks show real benefits; plan reversible data migration with corruption recovery and rollback.
- Add multi-select action feedback and safe, read-only missing-file repair guidance, with no destructive cleanup.
- **Acceptance:** measured improvement on baseline devices, preserved collections/favorites after migration, 10,000-item fixtures without app crash or missing records.

### 2.3.0 — Media player quality (P1)

**Goal:** Consistent offline playback across orientation, interruptions and background transitions.

**Implemented hardening slice (2.3.0):**
- Model transient/permanent audio-focus changes explicitly. Resume only after a transient interruption that began while playback was active; do not resume after permanent focus loss or headset removal.
- Treat the AUDIOFOCUS_GAIN callback as the grant and resume without issuing a second focus request.
- Persist the actual video playing/paused state when saving Activity state, including an explicit paused value.
- Add Robolectric regression tests for focus transitions and saved playback state.

**Still conditional / not claimed as complete:**
- Prototype Media3/ExoPlayer + MediaSession for audio and video, compare APK size, latency, energy and feature compatibility against existing MediaPlayer implementation.
- Improve lock-screen controls, orientation and PiP handoffs after physical-device review. Maintain position, playback speed and local-only playback.
- Only migrate if regression tests and device QA justify the change; otherwise strengthen current player.

- **Acceptance:** no unwanted audio overlap; pause/stop/focus controls behave correctly; reliable return from PiP, playlist handoff and notification behavior. Android 10–16 physical-device checks remain outstanding.

### 2.4.0 — Bilingual UX and accessibility (P1)

**Goal:** First-class Arabic RTL and English across the whole app.

**Implemented first slice (development, not yet published):**
- Linked accessible labels to language, format-quality and daily schedule hour selectors.
- Made download-window edits explicit with a Save action, preventing Spinner
  initialization or unsaved schedule changes from overwriting persisted time
  windows; existing nonzero minute precision is retained for unchanged hours.
- Allowed navigation/settings/storage controls to expand when font sizes grow.
- Marked shared section headings, announced selected navigation state in both
  languages and made storage scan results polite accessibility live regions.
- Added Robolectric checks for English/Arabic selectors, headings and navigation.

**Additional development hardening (not yet published):**
- Android 10 navigation now exposes selected state via content descriptions
  (Android 11+ retains native state descriptions).
- Storage scan failures no longer expose exception messages with possible local
  paths or URIs to the user interface.
- Added API 29 regression checks for bilingual selected-state announcements.

**Outstanding:** Audit all inline strings, keyboard navigation, color contrast,
200% system font scaling, TalkBack focus order, landscape/tablet layouts and
signed-APK device acceptance. No measured accessibility QA is claimed.

- Move remaining inline `tr(en,ar)` and raw English strings into Android resources with locale/RTL handling. Audit dynamic error states, format labels and notifications.
- Improve TalkBack labels, focus order, touch targets, color contrast, font scaling, empty/loading states and landscape/tablet layouts.
- Run accessibility audits and real-device QA in both languages; test text expansion and talkback independently of screenshot snapshots.
- **Acceptance:** no major untranslated user-facing flows, no clipped controls at 200% text scale and functional keyboard/screen reader navigation.

### 2.5.0 — Controlled source compatibility and offline integrity (P2)

**Goal:** Better reliability without bypasses or uncontrolled integration growth.

**Implemented first slice (development, not yet published):**
- Added public HTTP(S) direct-video and HTML5 page discovery for MP4/WebM/MOV
  sources, including common Open Graph video metadata and relative media URLs.
- Enabled public link paste/sharing, video preview/source selection, and durable
  queue entries using the existing transfer and MediaStore publishing flow.
- Added URL/redirect validation and public-only DNS for discovered media to
  avoid fetching local or private-network resources.
- Added offline HTML fixture, URL safety and queue recovery regression tests.

**Limitations:** Static public HTML5 sources only; no JavaScript execution,
DRM bypass, private/login media or generic HLS/DASH support. The user must
have permission to save the content. Physical-device link/source/resume QA
remains a release gate.

- Separate external source parsing behind validated provider adapters and contract fixtures.
- Add user-facing metadata provenance/privacy notes for third-party public X APIs; distinguish upstream unavailability from unsupported or private content.
- Validate optional source/quality labels and stable queuing when source formats change; focus on permitted public links.
- Expand explicit media checksum/integrity verification for locally completed transfers when feasible.
- **Acceptance:** no unsafe URL handling, no regression in current source fixtures, no silent source/quality substitution.

### 2.6.0 — Public MEGA video link integration (development slice)

**Goal:** Public, individually shared video files download into NOVA without
requiring a separate app, while never publishing unverified decrypted bytes.

**Implemented in development:**
- Recognize supported `mega.nz/file/handle#key` and legacy public file shares;
  reject spoofed, insecure and folder links.
- Query public metadata, decrypt filename locally and show file size and
  supported video extension in Explore.
- Transfer encrypted file chunks using MEGA's byte-segment API and existing
  queue controls, preserving encrypted partials when downloads pause.
- Reinspect the share each attempt for fresh transient transfer URLs; decrypt
  AES-CTR payload on-device and check full-file MEGA meta-MAC before MediaStore
  publishing. Drop plaintext staging files after verification failures.
- Add synthetic crypto/MAC, tampering, link/host and metadata regression tests.

**Release checks outstanding:** Validate public files you are permitted to
download on a real Android 10+ device, including quota failures, multi-chunk
data, pause/resume, large files, network loss, file playback and update from
the last public release. No credentials or private folders are supported.

### 2.6.0 — Build, quality and distribution hardening (P1/P2)

**Goal:** Repeatable and diagnosable builds.

- Add Android instrumented/device tests for lifecycle and media acceptance paths, a controlled size/performance baseline and report artifacts.
- Evaluate R8/resource shrinking and dependency size (including FFmpeg) in a separate benchmark-backed PR; do not enable blindly.
- Audit dependencies, GitHub Actions permissions, Dependabot/security scanning and reproducibility of version/build metadata.
- Add a privacy-first local diagnostics export that excludes media URLs, identifiers, credentials and file content unless the user explicitly opts in.
- **Acceptance:** signed-release verification, checksums, certificate continuity, manifest checks, documented rollback and actual device results.

### 3.0.0 — Stable architecture and experience milestone (release gate)

**Goal:** A verified, maintainable release, not a version bump only.

- Close P0 defects and critical regressions; merge only completed, independently verified features from earlier milestones.
- Validate Android 10–16 representative devices: downloads, reconnect, local media publishing, library migration, audio focus, PiP, RTL, TalkBack, battery/memory.
- Compare measured startup, library scrolling, queue reliability and APK size to 2.0.0 baselines.
- Sign with the existing production certificate, verify signer fingerprint and APK checksum, then publish release notes/tag only after approval and manual acceptance.
- **Acceptance:** release checklist signed off, CI green and production-signed artifact tested on physical devices.

## Execution ordering and gates

| Order | Version | Priority | Broad effort | Dependency |
| --- | --- | --- | --- | --- |
| 0 | 2.0.0 publication gate | P0 | Manual | [#76](https://github.com/MohammedEmad333/NOVADownloader/issues/76); no production claims without signed/mobile acceptance |
| 1 | 2.0.1 | P0 | Small–medium | [#77](https://github.com/MohammedEmad333/NOVADownloader/issues/77); evaluate before 2.0.0 publication |
| 2 | 2.1.0 | P0/P1 | Medium | 2.0.1 lifecycle stabilization |
| 3 | 2.2.0 | P1 | Medium–large | Library performance baseline and migration plan |
| 4 | 2.3.0 | P1 | Medium–large | Playback prototype/benchmark |
| 5 | 2.4.0 | P1 | Medium | UX and localization audit |
| 6 | 2.5.0 | P2 | Medium | Source contract fixtures |
| 7 | 2.6.0 | P1/P2 | Medium | Build/security baseline |
| 8 | 3.0.0 | Release gate | Medium QA | All critical acceptance gates |

Effort labels are **relative planning estimates**, not delivery promises or dates.

## Definition of done for each version

- [ ] Before coding: inspect repository state, issues, current code and existing tests; write focused acceptance criteria.
- [ ] Create feature/fix branch from current `main`; keep PR isolated and narrowly scoped.
- [ ] Add Robolectric/unit tests for normal, boundary, failure and no-data-loss cases.
- [ ] Run complete GitHub CI (unit tests, debug APK, unsigned release APK, artifact checksum).
- [ ] Review PR diff and mergeability; resolve CI failures before squash merge.
- [ ] Re-run and verify `main` CI, retain successful build/run and artifact links.
- [ ] Use a real Android device for lifecycle, performance, audio, PiP, layout and signature behavior. Do not claim physical verification without results.
- [ ] Update README, changelog/roadmap and test notes. For publication, follow the signed-release checklist; never expose signing secrets.

## Exclusions and decisions not yet made

- No account/login, cloud upload of private libraries, aggressive background service restart, automatic media deletion, private-content bypass or DRM circumvention.
- No automatic migration to Room or Media3 before benchmarking, rollback design and Android compatibility checks.
- No fabricated performance percentages, release deadlines or claims of production readiness without real device measurements.

## Supporting references

- [Release 2.0.0 acceptance](RELEASE_2_0_0.md)
- [Issue #76: signed release and physical device](https://github.com/MohammedEmad333/NOVADownloader/issues/76)
- [Issue #77: Android 15+ dataSync timeout](https://github.com/MohammedEmad333/NOVADownloader/issues/77)
- [Android FGS timeout guidance](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Android Media3 background playback](https://developer.android.com/media/media3/session/background-playback)
- [Android Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
