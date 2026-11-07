package io.github.khiqwq.framerecorder;

import com.sun.management.GarbageCollectionNotificationInfo;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

// 进入世界后按秒记录帧时间、GC 停顿与位置；扣除帧率限制器睡眠后仍超过 50 ms 的帧由看门狗线程抓取渲染线程调用栈。
public final class FrameRecorder {
    private static final long STALL_NS = 50_000_000L, LONG_STALL_NS = 250_000_000L;
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"), CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final ConcurrentLinkedQueue<long[]> GC_PAUSES = new ConcurrentLinkedQueue<>();
    private static volatile boolean recording;
    private static volatile long frameStart, frameId;
    // 帧率限制器（RenderSystem.limitDisplayFPS）的目标值与本帧内的睡眠时长
    private static volatile boolean inLimiter;
    private static volatile long limiterStart, limiterNs;
    private static volatile int limit;
    private static volatile Thread renderThread;
    private static volatile long sampledId = -1, longSampledId = -1;
    private static volatile StackTraceElement[] sample, longSample;
    private static volatile long sampleAt, longSampleAt;
    private static BufferedWriter csv, stalls;
    private static Path summaryPath;
    private static long secondStart, sessionStart, sessionFrames, sessionStalls, sessionLongStalls, sessionGcPauses;
    private static double sessionGcMs;
    private static final long[] histogram = new long[10_001];
    private static float[] seconds = new float[4096];
    private static int secondFrames, stall50, stall100;
    private static double secondSum, secondMax, secondWorkMax, secondLimiterMs;
    private static final Map<String, double[]> signatures = new HashMap<>();
    private static boolean gcListening;

    private FrameRecorder() {
    }

    public static synchronized void start(String server) {
        if (!recording) {
            try {
                Path dir = FabricLoader.getInstance().getGameDir().resolve("framerecorder");
                Files.createDirectories(dir);
                String stamp = LocalDateTime.now().format(FILE);
                csv = Files.newBufferedWriter(dir.resolve("frames-" + stamp + ".csv"), StandardCharsets.UTF_8);
                stalls = Files.newBufferedWriter(dir.resolve("stalls-" + stamp + ".log"), StandardCharsets.UTF_8);
                summaryPath = dir.resolve("summary-" + stamp + ".txt");
                csv.write("time,fps,avg_ms,p99_ms,max_ms,work_max_ms,fps_limit,limiter_ms_per_frame,stalls_50ms,stalls_100ms,gc_pauses,gc_pause_ms,gc_max_ms,heap_used_mb,heap_committed_mb,heap_max_mb,entities,server,dimension,x,y,z\n");
                stalls.write("# server " + server + ", started " + LocalDateTime.now() + "\n");
                Arrays.fill(histogram, 0);
                signatures.clear();
                sessionFrames = sessionStalls = sessionLongStalls = sessionGcPauses = 0;
                sessionGcMs = 0;
                secondFrames = stall50 = stall100 = 0;
                secondSum = secondMax = secondWorkMax = secondLimiterMs = 0;
                limiterNs = 0;
                GC_PAUSES.clear();
                listenGc();
                sessionStart = secondStart = frameStart = System.nanoTime();
                recording = true;
                Thread watchdog = new Thread(FrameRecorder::watch, "Frame Recorder stall sampler");
                watchdog.setDaemon(true);
                watchdog.start();
                FrameRecorderMod.LOGGER.info("Frame recording started for {} in {}", server, dir);
            } catch (IOException exception) {
                FrameRecorderMod.LOGGER.warn("Unable to start frame recording", exception);
            }
        }
    }

    public static synchronized void stop() {
        if (recording) {
            recording = false;
            try {
                csv.close();
                stalls.close();
                writeSummary();
            } catch (IOException exception) {
                FrameRecorderMod.LOGGER.warn("Unable to finish frame recording", exception);
            }
        }
    }

    // Minecraft.runTick 开头调用：上一帧到此结束。
    public static void frame() {
        long now = System.nanoTime();
        if (recording) {
            renderThread = Thread.currentThread();
            long duration = now - frameStart, slept = limiterNs, work = Math.max(0, duration - slept);
            long id = frameId;
            limiterNs = 0;
            frameStart = now;
            frameId = id + 1;
            double ms = duration / 1e6;
            secondWorkMax = Math.max(secondWorkMax, work / 1e6);
            secondLimiterMs += slept / 1e6;
            if (secondFrames == seconds.length) seconds = Arrays.copyOf(seconds, seconds.length * 2);
            seconds[secondFrames++] = (float) ms;
            secondSum += ms;
            secondMax = Math.max(secondMax, ms);
            histogram[(int) Math.min(histogram.length - 1, Math.round(ms * 10))]++;
            sessionFrames++;
            if (work >= STALL_NS) {
                stall50++;
                if (work >= 100_000_000L) stall100++;
                recordStall(id, work / 1e6, now);
            }
            if (now - secondStart >= 1_000_000_000L) writeSecond(now);
        }
    }

    private static void watch() {
        while (recording) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException exception) {
                return;
            }
            Thread thread = renderThread;
            long now = System.nanoTime();
            if (inLimiter) continue;
            long id = frameId, elapsed = now - frameStart - limiterNs;
            if (thread != null && elapsed >= STALL_NS && sampledId != id) {
                sample = thread.getStackTrace();
                sampleAt = elapsed;
                sampledId = id;
            }
            if (thread != null && elapsed >= LONG_STALL_NS && longSampledId != id) {
                longSample = thread.getStackTrace();
                longSampleAt = elapsed;
                longSampledId = id;
            }
        }
    }

    public static void limiterEnter(int fps) {
        if (recording) {
            limit = fps;
            limiterStart = System.nanoTime();
            inLimiter = true;
        }
    }

    public static void limiterExit() {
        if (inLimiter) {
            limiterNs += System.nanoTime() - limiterStart;
            inLimiter = false;
        }
    }

    private static void recordStall(long id, double ms, long now) {
        sessionStalls++;
        if (ms >= 250) sessionLongStalls++;
        double gc = 0;
        StringBuilder gcText = new StringBuilder();
        for (long[] pause : GC_PAUSES) {
            if (pause[0] >= now - (long) (ms * 1e6) - 5_000_000L && pause[0] <= now) {
                gc += pause[1] / 1000.0;
                gcText.append(gcText.isEmpty() ? "" : "+").append(pause[1] / 1000.0).append("ms");
            }
        }
        StackTraceElement[] stack = sampledId == id ? sample : null;
        String signature = stack == null ? "(not sampled)" : signature(stack);
        double[] stats = signatures.computeIfAbsent(signature, key -> new double[2]);
        stats[0]++;
        stats[1] += ms;
        try {
            stalls.write(String.format("%s stall %.1f ms work%s  %s%n", LocalDateTime.now().format(CLOCK), ms,
                    gc > 0 ? "  gc " + gcText : "", signature));
            if (stack != null) stalls.write(format(stack, sampleAt));
            if (longSampledId == id && longSample != null) stalls.write(format(longSample, longSampleAt));
            stalls.flush();
        } catch (IOException ignored) {
        }
    }

    private static String format(StackTraceElement[] stack, long at) {
        StringBuilder text = new StringBuilder(String.format("    sampled at %.0f ms:%n", at / 1e6));
        for (int i = 0; i < Math.min(stack.length, 28); i++) text.append("      ").append(stack[i]).append('\n');
        return text.toString();
    }

    // 卡顿归类：最靠近栈顶的模组帧，以及最靠近栈顶的非 JDK 帧。
    static String signature(StackTraceElement[] stack) {
        String top = null, mod = null;
        for (StackTraceElement frame : stack) {
            String type = frame.getClassName();
            boolean jdk = type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun.") || type.startsWith("com.sun.") || type.startsWith("org.lwjgl.");
            if (top == null && !jdk) top = type + "." + frame.getMethodName();
            if (mod == null && !jdk && !type.startsWith("net.minecraft.") && !type.startsWith("com.mojang.") && !type.startsWith("net.fabricmc.")
                    && !type.startsWith("io.github.khiqwq.framerecorder.") && !type.startsWith("org.spongepowered.") && !type.startsWith("com.llamalad7.")) {
                mod = type + "." + frame.getMethodName();
            }
        }
        String first = stack.length > 0 ? stack[0].getClassName() + "." + stack[0].getMethodName() : "?";
        return "top=" + first + " | game=" + (top == null ? "-" : top) + " | mod=" + (mod == null ? "-" : mod);
    }

    private static void writeSecond(long now) {
        float[] frames = Arrays.copyOf(seconds, secondFrames);
        Arrays.sort(frames);
        double elapsed = (now - secondStart) / 1e9;
        int pauses = 0;
        double pauseMs = 0, pauseMax = 0;
        long[] pause;
        while ((pause = GC_PAUSES.peek()) != null && pause[0] <= now) {
            GC_PAUSES.poll();
            if (pause[0] >= secondStart - 5_000_000_000L) {
                pauses++;
                pauseMs += pause[1] / 1000.0;
                pauseMax = Math.max(pauseMax, pause[1] / 1000.0);
            }
        }
        sessionGcPauses += pauses;
        sessionGcMs += pauseMs;
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        var player = minecraft.player;
        var server = minecraft.getCurrentServer();
        try {
            csv.write(String.format("%s,%.1f,%.2f,%.2f,%.2f,%.2f,%d,%.2f,%d,%d,%d,%.1f,%.1f,%d,%d,%d,%d,%s,%s,%.0f,%.0f,%.0f%n",
                    LocalDateTime.now().format(CLOCK), secondFrames / elapsed, secondFrames == 0 ? 0 : secondSum / secondFrames,
                    frames.length == 0 ? 0 : frames[Math.min(frames.length - 1, (int) Math.floor(frames.length * 0.99))], secondMax,
                    secondWorkMax, limit, secondFrames == 0 ? 0 : secondLimiterMs / secondFrames, stall50, stall100, pauses, pauseMs, pauseMax, heap.getUsed() >> 20, heap.getCommitted() >> 20, heap.getMax() >> 20,
                    level == null ? 0 : level.getEntityCount(), server == null ? (minecraft.isLocalServer() ? "singleplayer" : "-") : server.ip,
                    level == null ? "-" : level.dimension().identifier(), player == null ? 0 : player.getX(), player == null ? 0 : player.getY(), player == null ? 0 : player.getZ()));
            csv.flush();
        } catch (IOException ignored) {
        }
        secondStart = now;
        secondFrames = stall50 = stall100 = 0;
        secondSum = secondMax = secondWorkMax = secondLimiterMs = 0;
    }

    private static void writeSummary() throws IOException {
        double minutes = (System.nanoTime() - sessionStart) / 6e10;
        StringBuilder text = new StringBuilder();
        text.append(String.format("frames %d over %.1f min, average %.1f FPS%n", sessionFrames, minutes, sessionFrames / (minutes * 60)));
        text.append(String.format("frame time p50 %.1f ms, p99 %.1f ms, p99.9 %.1f ms (1%% low %.1f FPS)%n",
                percentile(0.5), percentile(0.99), percentile(0.999), 1000 / Math.max(0.1, percentile(0.99))));
        text.append(String.format("stalls (work time, frame limiter excluded) >=50 ms: %d, >=250 ms: %d; GC pauses %d totalling %.0f ms%n%n", sessionStalls, sessionLongStalls, sessionGcPauses, sessionGcMs));
        text.append("stall sources (count, total ms):\n");
        List<Map.Entry<String, double[]>> entries = signatures.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1])).toList();
        for (var entry : entries) text.append(String.format("  %4.0f  %8.0f ms  %s%n", entry.getValue()[0], entry.getValue()[1], entry.getKey()));
        Files.writeString(summaryPath, text.toString(), StandardCharsets.UTF_8);
    }

    private static double percentile(double fraction) {
        long total = Arrays.stream(histogram).sum(), target = (long) Math.ceil(total * fraction), seen = 0;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i];
            if (seen >= target && total > 0) return i / 10.0;
        }
        return 0;
    }

    private static void listenGc() {
        if (!gcListening) {
            gcListening = true;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                String name = bean.getName();
                // 只统计停顿：G1 并发周期与 ZGC Cycles 不是停顿
                if (bean instanceof NotificationEmitter emitter && !name.contains("Concurrent") && !name.contains("Cycles")) {
                    emitter.addNotificationListener((notification, handback) -> {
                        if (recording && GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                            var info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
                            GC_PAUSES.add(new long[]{System.nanoTime(), info.getGcInfo().getDuration() * 1000});
                        }
                    }, null, null);
                }
            }
        }
    }
}
