# Android memory handling — Winlator Ludashi 3.1

## Purpose

This branch improves session foreground-service reliability and reports prior
Android-level memory-related process exits. **It does not bypass LMKD, disable
the Android 17 Memory Limiter, or increase physical RAM.**

## Changes

1. `MainActivity` always creates its foreground-service notification channel
   when supported, and starts the service even if `POST_NOTIFICATIONS` was
   denied. Android allows foreground services without this notification
   permission (the notification might only appear in Android's active-apps UI).
2. `NotificationService` no longer stops itself when notification permission
   is denied. Its notification now opens `MainActivity` explicitly.
3. `LowMemoryExitDiagnostics` checks the Android 11+ historical process-exit
   API at startup. If available, a dialog distinguishes:
   - `REASON_LOW_MEMORY` (LMKD);
   - Android 17 `REASON_MEMORY_LIMITER` (reason code 17);
   - SIGKILL (9), which is **not proof** of an LMKD kill.

The dialog includes last sampled PSS/RSS, not an exact peak. It only shows
each historical event once. This API may not report the demise of a Wine/Box64
guest child even if that child is the one that failed.

## No-root test checklist

- Install a **test** APK built from this branch; preserve a backup of existing
  containers and game saves before installing. Do not uninstall your working
  version until you have a backup.
- With `POST_NOTIFICATIONS` denied, enter the app: verify the foreground
  service can run and the app still starts games.
- Reproduce a large-memory game shutdown. Reopen Winlator and read the
  diagnosis dialog, if Android produced a matching process-exit record.
- If the game alone crashes and Winlator stays open, collect Wine/Box64 logs.
  Android's process exit API cannot conclusively identify all guest crashes.
- Compare game startup, background/foreground transitions and memory
  behavior against an unmodified build using the **same** game and settings.
- Run `./gradlew assembleDebug` in CI and investigate build failures rather
  than assuming the build is good.

## Limitations

Android owns LMKD, cgroup process limits and the Linux kernel memory policy.
An ordinary app cannot lower its own OOM score below the limits imposed by the
system or override memory cgroup hard/soft limits. A foreground service improves
the OS importance classification in certain circumstances, but cannot prevent
termination when memory pressure is critical.

This change does not add root, Shizuku, ADB or privileged permissions.

References:
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://developer.android.com/reference/android/app/ApplicationExitInfo
- https://source.android.com/docs/core/perf/lmkd
- https://source.android.com/docs/core/perf/memory-limiter

## No-root reverse engineering probe (new)

While a game is active, `MemoryPressureProbe` records a bounded internal
trace every 8 seconds:

- Android `availMem`, `threshold`, and `lowMemory` state;
- system memory PSI `some avg10`, when exposed;
- Winlator host PID and initial guest launcher PID, RSS, swap and
  `oom_score_adj`;
- each readable cgroup v2 `memory.current`, `memory.high`,
  `memory.swap.max`, and `memory.events` state.

Navigate to **Winlator Settings → Diagnostico de memoria (LMKD / Android 17)**
to view and copy the 12 most recent readings. Log file
`files/winlator_memory_trace.log` is rotated at roughly 128 KiB; internal
storage is private to the app.

As an early pressure mitigation, the probe rate-limits cleanup of **Glide UI
image cache** to once per two minutes if Android reports low memory, available
RAM approaches the threshold, or readable cgroup consumption is >=90% of
`memory.high`. The foreground `Activity` performs this on the UI thread.
The cleanup intentionally leaves Wine, Box64, XServer surfaces and Vulkan game
resources untouched. Effectiveness must be measured; it is not a guaranteed
LMKD workaround and does not create additional RAM.

The guest PID is the launcher process; it might not be the heaviest Wine game
process, and Android may hide procfs and cgroup files. Missing fields are not
evidence of no pressure. Do not write to `oom_score_adj`, `memory.high` or
Android system properties: ordinary application permissions cannot reliably
override system memory policy.

### Interpreting samples

1. If `cgroupCurrentBytes` remains near `cgroupHighBytes`, and
   `cgroupEvents` increases `high`/ `oom`/ `oom_kill`, investigate
   Android 17 Memory Limiter and its implementation on the target firmware.
2. If system PSI increases and `ApplicationExitInfo` reports
   `REASON_LOW_MEMORY`, LMKD is implicated.
3. If host is alive but the Wine/Box64 process vanished, inspect the guest
   native memory usage and crash output. The ApplicationExitInfo record may
   be missing for such Linux guest children.
4. The Winlator HUD's total system RAM number is **not** the PSS/RSS of a
   single process or its cgroup; a crash at 6.5 GB total does not establish a
   6.5 GB per-app limit.

### AOSP references

- https://source.android.com/docs/core/perf/lmkd
- https://source.android.com/docs/core/perf/memory-limiter
- https://developer.android.com/topic/performance/memory/guide/kswapd-lmkd-interaction
- https://developer.android.com/reference/android/app/ApplicationExitInfo
