package com.winlator.cmod.core;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.OsConstants;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * Reports previous Android process exits to the user without root or ADB.
 *
 * This is diagnostic only. It cannot disable Android's LMKD or Memory Limiter,
 * and exits of arbitrary Wine/Box64 guest processes may not be recorded here.
 */
public final class LowMemoryExitDiagnostics {
    private static final String TAG = "WinlatorMemory";
    private static final String PREFS_NAME = "low_memory_exit_diagnostics";
    private static final String LAST_SEEN_TIMESTAMP = "last_seen_exit_timestamp";

    // REASON_MEMORY_LIMITER arrived with Android 17 / API 37.2.
    // Keep the numeric value to support this fork's compileSdk 34.
    private static final int REASON_MEMORY_LIMITER = 17;

    private LowMemoryExitDiagnostics() {}

    public static void showIfNeeded(AppCompatActivity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || activity.isFinishing()) return;

        try {
            ActivityManager manager =
                    (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;

            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long lastSeen = prefs.getLong(LAST_SEEN_TIMESTAMP, 0L);
            long newest = lastSeen;
            ApplicationExitInfo selected = null;
            List<ApplicationExitInfo> exits =
                    manager.getHistoricalProcessExitReasons(null, 0, 20);

            for (ApplicationExitInfo info : exits) {
                String processName = info.getProcessName();
                if (processName == null || !processName.startsWith(activity.getPackageName())) {
                    continue;
                }
                newest = Math.max(newest, info.getTimestamp());
                if (info.getTimestamp() <= lastSeen) continue;

                int reason = info.getReason();
                boolean memoryExit = reason == ApplicationExitInfo.REASON_LOW_MEMORY
                        || reason == REASON_MEMORY_LIMITER;
                boolean possibleKill = reason == ApplicationExitInfo.REASON_SIGNALED
                        && info.getStatus() == OsConstants.SIGKILL;

                if ((memoryExit || possibleKill) &&
                        (selected == null || info.getTimestamp() > selected.getTimestamp())) {
                    selected = info;
                }
            }

            // Show any historical event once rather than repeating a warning every launch.
            if (newest > lastSeen) {
                prefs.edit().putLong(LAST_SEEN_TIMESTAMP, newest).apply();
            }
            if (selected == null || activity.isFinishing()) return;

            String reason;
            switch (selected.getReason()) {
                case ApplicationExitInfo.REASON_LOW_MEMORY:
                    reason = "LMKD: o Android informou encerramento por falta de memoria.";
                    break;
                case REASON_MEMORY_LIMITER:
                    reason = "Memory Limiter: o processo excedeu um limite do sistema Android.";
                    break;
                default:
                    reason = "SIGKILL (9): o processo recebeu um sinal de encerramento. "
                            + "Isso nao prova que tenha sido o LMKD.";
                    break;
            }

            StringBuilder message = new StringBuilder(reason);
            if (selected.getPss() > 0) {
                message.append("\nPSS amostrado: ")
                        .append(selected.getPss() / 1024L).append(" MiB");
            }
            if (selected.getRss() > 0) {
                message.append("\nRSS amostrado: ")
                        .append(selected.getRss() / 1024L).append(" MiB");
            }
            message.append("\n\nEsses valores sao amostras anteriores, nao o pico exato. "
                    + "Um fechamento exclusivo do Wine/Box64 pode nao aparecer aqui.");

            Log.w(TAG, "Previous process exit: " + reason
                    + " reasonCode=" + selected.getReason()
                    + " status=" + selected.getStatus());
            new AlertDialog.Builder(activity)
                    .setTitle("Diagnostico de memoria do Winlator")
                    .setMessage(message.toString())
                    .setPositiveButton("OK", null)
                    .show();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read previous process exits", error);
        }
    }
}
