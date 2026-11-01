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
