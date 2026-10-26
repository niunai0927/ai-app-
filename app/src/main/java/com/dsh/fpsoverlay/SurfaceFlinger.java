package com.dsh.fpsoverlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 SurfaceFlinger 的图层信息。
 *
 * 原理：SurfaceFlinger 记录了每个图层最近 128 帧的「期望上屏时间 / 实际上屏时间」。
 * dumpsys SurfaceFlinger --latency &lt;图层名&gt; 输出的就是这些纳秒时间戳，
 * 相邻两帧的差值就是真实帧时间，倒过来就是真实帧率（和 PC 上 RTSS 的 present FPS 同源）。
 *
 * 之所以能读到「游戏」的帧，是因为我们读的是图层（Layer）级别，而不是进程级别：
 * 只要拿到游戏 SurfaceView 对应的图层名，拿到的就是它真正上屏的节奏。
 */
public final class SurfaceFlinger {

    /** 形如 com.pkg/com.pkg.MainActivity 的包名 + Activity 片段。 */
    private static final Pattern PKG_PATTERN =
            Pattern.compile("([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)/[A-Za-z0-9_.$]+");

    private SurfaceFlinger() {
    }

    private static volatile String homePkg = null;
    private static volatile boolean homeQueried = false;

    /** 桌面（Launcher）包名，用来避免把桌面当成「正在运行的游戏」。 */
    public static String homePackage() {
        if (homeQueried) {
            return homePkg;
        }
        String out = RootShell.run("cmd package resolve-activity --brief "
                + "-a android.intent.action.MAIN -c android.intent.category.HOME", 4000);
        String pkg = matchPackage(out);
        if (pkg != null) {
            homePkg = pkg;
            homeQueried = true;
        }
        return pkg;
    }

    /** 一次 --latency 的解析结果。 */
    public static final class Latency {
        public final List<Long> stamps = new ArrayList<Long>();
        public long refreshNs = 0L;
    }

    // ---------------------------------------------------------------- 前台应用

    /** 返回当前前台应用包名；判断不出来返回 null。 */
    public static String foregroundPackage() {
        String out = RootShell.run("dumpsys window windows | grep -m 1 mCurrentFocus", 4000);
        String pkg = matchPackage(out);
        if (pkg != null) {
            return normalize(pkg);
        }
        out = RootShell.run(
                "dumpsys activity activities | grep -m 1 -E 'topResumedActivity|mResumedActivity'",
                4000);
        pkg = matchPackage(out);
        if (pkg != null) {
            return normalize(pkg);
        }
        out = RootShell.run("dumpsys window | grep -m 1 mCurrentFocus", 6000);
        return normalize(matchPackage(out));
    }

    private static String normalize(String pkg) {
        if (pkg == null) {
            return null;
        }
        if ("android".equals(pkg) || pkg.startsWith("com.android.systemui")) {
            return null;
        }
        return pkg;
    }

    private static String matchPackage(String text) {
        if (text == null || text.length() == 0) {
            return null;
        }
        Matcher m = PKG_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    // ------------------------------------------------------------------ 图层名

    /** dumpsys SurfaceFlinger --list 的全部图层名。 */
    public static List<String> listLayers() {
        String out = RootShell.run("dumpsys SurfaceFlinger --list", 5000);
        List<String> layers = new ArrayList<String>();
        if (out == null) {
            return layers;
        }
        String[] lines = out.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) {
                continue;
            }
            // 过滤掉明显的日志行、错误行
            if (line.startsWith("*") || line.startsWith("grep:") || line.startsWith("sh:")) {
                continue;
            }
            if (!line.contains("/") && !line.contains("#")) {
                continue;
            }
            layers.add(line);
        }
        return layers;
    }

    /** 从图层列表里挑出属于 pkg 的候选图层，越可能带帧的排越前。 */
    public static List<String> candidates(List<String> layers, String pkg) {
        List<String> out = new ArrayList<String>();
        if (layers == null || pkg == null) {
            return out;
        }
        for (String name : layers) {
            if (!name.contains(pkg)) {
                continue;
            }
            if (name.startsWith("Background for")) {
                continue;
            }
            if (name.contains("Dim Layer")) {
                continue;
            }
            if (name.contains("ScreenDecorOverlay") || name.contains("Letterbox")) {
                continue;
            }
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int ra = rank(a);
                int rb = rank(b);
                if (ra != rb) {
                    return rb - ra;
                }
                return a.length() - b.length();
            }
        });
        return out;
    }

    private static int rank(String name) {
        int score = 0;
        if (name.startsWith("SurfaceView[")) {
            score += 4;
        }
        if (name.endsWith("#0")) {
            score += 1;
        }
        if (name.contains("BLAST")) {
            score += 1;
        }
        return score;
    }

    /**
     * 找出真正有帧数据的图层：依次试候选层，取采样点最多的那个。
     * 一个候选都没有时返回 null。
     */
    public static String resolve(String pkg) {
        List<String> all = listLayers();
        List<String> cand = candidates(all, pkg);
        if (cand.isEmpty()) {
            return null;
        }
        String best = null;
        int bestCount = -1;
        for (int i = 0; i < cand.size() && i < 3; i++) {
            String name = cand.get(i);
            Latency l = readLatency(name);
            if (l != null && l.stamps.size() > bestCount) {
                bestCount = l.stamps.size();
                best = name;
            }
        }
        if (best != null && bestCount > 0) {
            return best;
        }
        // 都还没出帧，先拿排第一的候选继续轮询，稍后会自动重新识别。
        return cand.get(0);
    }

    // -------------------------------------------------------------- 延迟数据

    public static Latency readLatency(String layer) {
        if (layer == null || layer.length() == 0) {
            return null;
        }
        String out = RootShell.run("dumpsys SurfaceFlinger --latency '" + layer + "'", 3000);
        return parse(out);
    }

    /** 解析 --latency 文本。格式：首行刷新周期(ns)，之后每行「期望时间 实际时间 就绪时间」。 */
    public static Latency parse(String out) {
        Latency lat = new Latency();
        if (out == null || out.length() == 0) {
            return lat;
        }
        String[] lines = out.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) {
                continue;
            }
            String[] t = line.split("\\s+");
            if (t.length == 1) {
                long v = parseLong(t[0]);
                if (v > 0 && v < 1000000000L) {
                    lat.refreshNs = v;
                }
                continue;
            }
            if (t.length < 3) {
                continue;
            }
            long actual = parseLong(t[1]);
            if (actual <= 0 || actual == Long.MAX_VALUE) {
                continue;
            }
            lat.stamps.add(actual);
        }
        Collections.sort(lat.stamps);
        return lat;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1L;
        }
    }
}
