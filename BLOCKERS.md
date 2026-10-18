# SIS-683 / ZJS-F9 validation blockers

Implementation is complete, but acceptance is NOT verified.

## SIS-609 attempt (2026-10-17) — reinstall gate not met, still blocked

Ran the SIS-609 correction lane (per lane-spec /tmp/lane-specs/SIS-609.md, instructions of 11-oct). Preconditions failed at the explicit disk gates:

- `df -h /mnt/volume-molty-1`: 1.3 GB free — below the spec's "no empieces" threshold of 2.5 GB for step 0 and even below the 1.5 GB gate for the test run. Root `/` is also tight: 1.7 GB free at 96%.
- Gradle 9.2.1 dist `/mnt/volume-molty-1/data/gradle/wrapper/dists/gradle-9.2.1-bin/<hash>/gradle-9.2.1/bin/gradle` — ABSENT (`find` across /mnt/volume-molty-1, /opt, /root/.gradle, /root/.cache, /root/infra, /usr/local: nothing). Reinstalling it (`gradle-install.sh`) needs ~360 MB on the volume.
- Android SDK `/mnt/volume-molty-1/android/sdk` — ABSENT (purged 2026-10-11 per `/root/infra/state/android-sdk-manifest.txt`); reinstall needs ~0.9 GB on the volume, same filesystem.
- `GRADLE_USER_HOME` = `/mnt/volume-molty-1/data/gradle` (`/root/.gradle` symlinks there); its `caches/` is empty, so a full network download of AGP 8.5.2 + Kotlin (~0.7–1 GB more) would be required — this cannot fit in the remaining space and would fall below the spec's 300 MB abort line during the download.

Per the spec stop conditions, step 0 was NOT started: `android-sdk-install.sh` and `gradle-install.sh` were not executed, `verification-metadata.xml` was not touched (the 4 Kotlin 2.2.20 checksums remain unadded), no `--no-verification` or version override was used, and the ZJS-F9 commit (4972683) is untouched. Nothing was deleted.

Unblock: bring `/mnt/volume-molty-1` above 2.5 GB free, then follow SIS-609 steps 0–6 (`android-sdk-install.sh` + `gradle-install.sh`, network test run, checksum entries, `G --stop`) and delete this file once the `*Journal*` task is green.

## SIS-683 re-dispatch re-verification (2026-10-17 ~17:21 CST)

Re-ran the SIS-683 correction lane (lane-spec /tmp/lane-specs/SIS-683.md) against HEAD (4972683 + f9ee9e8). Review round-1 items all verified present in the committed tree:

- JournalPrefill.kt:17 — `data object BannerOnly : PrefillDecision`, no `answers` payload.
- InsightsScreen.kt:275-277 — the `BannerOnly` branch only sets `preFilledFromYesterday = true`; it does not assign `answers` and does not upsert.
- `grep -rn prefillVisualOnly android/` → 0 hits: the reviewer's over-broad global clear guard is gone; `onClear` (InsightsScreen.kt:394-399) deletes the real row and bumps `journalSeq` (original behaviour preserved).
- `git status` clean on `journal-sync-noop`; `git diff --check` clean; both files exist in commit 4972683 with SIS-683 in the commit body.

Acceptance (green `*Journal*` task + compileDebugKotlin) still NOT verified: the SIS-609 disk gates fail again on this attempt — `df -h`: volume 1.1 GB free, root `/` 1.7 GB at 96%; `/mnt/volume-molty-1/data/gradle/wrapper/dists/` contains only `CACHEDIR.TAG` (no gradle-9.2.1-bin); `/mnt/volume-molty-1/android/sdk` absent. Per the SIS-609 stop conditions step 0 was not started; no reinstalls, no verification bypass, no version override, no wrapper changes, nothing deleted. The "Unblock" line above still stands.

## Build environment

The required cached Gradle 9.2.1 executable could not be found under /mnt/volume-molty-1, /opt, /root/.gradle, /root/.cache, /root/.hermes/cache, /root/infra, /usr/local or /tmp. The installed /usr/bin/gradle reports 4.4.1 outside the Android project. Inside the project it unexpectedly delegates to the unchanged 8.7 wrapper and downloaded that distribution even with --offline. This was not intended; no wrapper files were changed.

Executed from /root/noop/android:

- `gradle --offline --no-daemon :app:testFullDebugUnitTest --tests '*Journal*'`
- `gradle --offline --no-daemon :app:compileDebugKotlin`

Both failed before project configuration at `/mnt/volume-molty-1/data/gradle/init.d/single-version.gradle:20`: machine policy requires Gradle 9.2.1 but the build runs 8.7. No version override, verification bypass, service restart, deployment or APK build was performed. Restore the approved cached Gradle 9.2.1 executable and rerun the Journal test task (which also compiles app Kotlin). Dependency resolution remains unverified; no checksum entries were invented.

## Criterion evidence

1. Journal tests green: BLOCKED by the initialization policy; zero tests executed.
2. Decision coverage: JournalPrefillTest contains six JVM tests covering sync-on banner-only, sync-off persistence, non-today offsets, existing today answers, repeated banner reload/real today answers with sync on, and empty yesterday. Not executed.
3. Kotlin compilation: BLOCKED by the same initialization policy.
4. Implementation: BannerOnly is a data object without answers; its UI branch does not assign answers or upsert. Today's chips use real today rows. Removed the global clear guard so clearing real rows today and on previous days retains the original delete behavior. Existing sync-off upsert is unchanged. `git diff --check` passed.
5. Commit: uses /root/noop on journal-sync-noop, the original spec's explicit repository exception. SIS-683's later signature requirement (Claudio) supersedes the original Juan signature; the original subject is retained with SIS-683 in the commit body. No workspace production files were edited.

The pre-existing uncommitted ZJS-F9 implementation is included with this correction because it is required by the spec and had no prior commit. No release/deploy acceptance is claimed.

## SIS-683 re-dispatch (2026-10-18)

Code at HEAD already matches review round 1 (BannerOnly without answers; no prefillVisualOnly guard; onClear deletes the real row). Validation still unmet: `/mnt/volume-molty-1` has 1018 MB free (< 2.5 GB gate), no Gradle 9.2.1 dist, no Android SDK. No changes to code; nothing reinstalled or bypassed.
