package com.winlator.cmod.core;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Read-only, no-root probe for Android memory pressure and process limits.
 *
 * This does not change cgroups or oom_score_adj. The guest process and Android
 * host can belong to different process groups; record both when readable.
 */
public final class MemoryPressureProbe {
    private static final String TAG = "WinlatorMemory";
    private static final String TRACE_FILE = "winlator_memory_trace.log";
    private static final long MAX_TRACE_BYTES = 128 * 1024L;
    private final Context context;
    private ScheduledExecutorService executor;
    private final Runnable cacheReclaimer;
    private long lastCacheReclaimMs = 0L;

    public MemoryPressureProbe(Context context, Runnable cacheReclaimer) {
        this.context = context.getApplicationContext();
        this.cacheReclaimer = cacheReclaimer;
    }

    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "winlator-memory-probe");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                sample();
            } catch (Exception e) {
                Log.w(TAG, "Memory sample unavailable", e);
            }
        }, 0, 8, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (executor == null) return;
        executor.shutdownNow();
        executor = null;
    }

    private void sample() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        ActivityManager.MemoryInfo system = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(system);

        int hostPid = Process.myPid();
        int guestPid = GuestProgramLauncherComponent.getActiveGuestPid();

        StringBuilder sample = new StringBuilder(280);
        sample.append("epochMs=").append(System.currentTimeMillis())
                .append(" availMiB=").append(system.availMem / 1048576L)
                .append(" thresholdMiB=").append(system.threshold / 1048576L)
                .append(" systemLow=").append(system.lowMemory)
                .append(" psiSomeAvg10=").append(psiSomeAvg10())
                .append(" host=").append(processSample(hostPid));

        if (guestPid > 0 && guestPid != hostPid) {
            sample.append(" guest=").append(processSample(guestPid));
        }

        appendTrace(sample.toString());

        boolean approachingSystemThreshold = system.threshold > 0
                && system.availMem <= system.threshold * 2L;
        boolean approachingCgroupLimit = nearMemoryHigh(hostPid)
                || (guestPid > 0 && nearMemoryHigh(guestPid));
        if (system.lowMemory || approachingSystemThreshold || approachingCgroupLimit) {
            Log.w(TAG, "Pressure detected (read-only): " + sample);
            long now = System.currentTimeMillis();
            // At most once every two minutes: avoid repeatedly flushing UI caches.
            if (cacheReclaimer != null && now - lastCacheReclaimMs >= 120000L) {
                lastCacheReclaimMs = now;
                cacheReclaimer.run();
            }
        }
    }

    private static boolean nearMemoryHigh(int pid) {
        String group = readSmallFile(new File("/proc/" + pid + "/cgroup"), 2048);
        for (String line : group.split("\\n")) {
            if (!line.startsWith("0::")) continue;
            String relative = line.substring(3).trim();
            if (!relative.startsWith("/") || relative.contains("..")) return false;
            File dir = new File("/sys/fs/cgroup", relative.substring(1));
            try {
                long current = Long.parseLong(
                        readSmallFile(new File(dir, "memory.current"), 64).trim());
                long high = Long.parseLong(
                        readSmallFile(new File(dir, "memory.high"), 64).trim());
                return high > 0 && current >= (high / 10L) * 9L;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private static String processSample(int pid) {
        StringBuilder result = new StringBuilder();
        result.append("{pid:").append(pid);
        String status = readSmallFile(new File("/proc/" + pid + "/status"), 16 * 1024);
        result.append(",rssKiB:").append(statusValue(status, "VmRSS:"))
                .append(",swapKiB:").append(statusValue(status, "VmSwap:"))
                .append(",oomAdj:").append(readSmallFile(
                        new File("/proc/" + pid + "/oom_score_adj"), 64).trim());

        String group = readSmallFile(new File("/proc/" + pid + "/cgroup"), 2048);
        String relative = null;
        for (String line : group.split("\n")) {
            if (line.startsWith("0::")) {
                relative = line.substring(3).trim();
                break;
            }
        }
        if (relative != null && relative.startsWith("/") && !relative.contains("..")) {
            File dir = new File("/sys/fs/cgroup", relative.substring(1));
            String current = readSmallFile(new File(dir, "memory.current"), 64).trim();
            String high = readSmallFile(new File(dir, "memory.high"), 64).trim();
            String swapMax = readSmallFile(new File(dir, "memory.swap.max"), 64).trim();
            String events = readSmallFile(new File(dir, "memory.events"), 2048)
                    .trim().replace('\n', ',').replace(' ', ':');
            if (!current.isEmpty() || !high.isEmpty()) {
                result.append(",cgroupCurrentBytes:").append(current)
                        .append(",cgroupHighBytes:").append(high)
                        .append(",cgroupSwapMaxBytes:").append(swapMax)
                        .append(",cgroupEvents:").append(events);
            }
        }
        return result.append('}').toString();
    }

    private static String statusValue(String status, String name) {
        for (String line : status.split("\n")) {
            if (line.startsWith(name)) {
                return line.substring(name.length()).trim().replaceAll("\\s+", "");
            }
        }
        return "unavailable";
    }

    private static String psiSomeAvg10() {
        String pressure = readSmallFile(new File("/proc/pressure/memory"), 1024);
        for (String line : pressure.split("\n")) {
            if (!line.startsWith("some ")) continue;
            for (String item : line.split(" ")) {
                if (item.startsWith("avg10=")) return item.substring(6);
            }
        }
        return "unavailable";
    }

    private static String readSmallFile(File file, int maxChars) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            char[] buffer = new char[512];
            int n;
            while (data.length() < maxChars && (n = reader.read(buffer, 0,
                    Math.min(buffer.length, maxChars - data.length()))) != -1) {
                data.append(buffer, 0, n);
            }
            return data.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void appendTrace(String sample) {
        File file = new File(context.getFilesDir(), TRACE_FILE);
        try {
            // Rotate without retaining unbounded data on internal storage.
            if (file.length() > MAX_TRACE_BYTES) {
                File previous = new File(context.getFilesDir(), TRACE_FILE + ".old");
                if (previous.exists()) previous.delete();
                if (!file.renameTo(previous)) {
                    Log.w(TAG, "Could not rotate memory trace");
                }
            }
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write((sample + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not write memory sample", e);
        }
    }

    public static String readRecentSamples(Context context) {
        File file = new File(context.getFilesDir(), TRACE_FILE);
        if (!file.exists()) return "Ainda nao ha amostras. Abra um jogo e tente reproduzir o fechamento.";

        ArrayDeque<String> lines = new ArrayDeque<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                lines.addLast(line);
                if (lines.size() > 12) lines.removeFirst();
            }
        } catch (Exception e) {
            return "Nao foi possivel ler o registro: " + e.getClass().getSimpleName();
        }
        StringBuilder result = new StringBuilder("Ultimas amostras (8 s). Campos vazios indicam leitura bloqueada pelo Android.\n\n");
        for (String line : lines) result.append(line).append("\n");
        return result.toString();
    }
}
