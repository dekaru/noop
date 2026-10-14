# SIS-683 / ZJS-F9 validation blockers

Implementation is complete, but acceptance is NOT verified.

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
