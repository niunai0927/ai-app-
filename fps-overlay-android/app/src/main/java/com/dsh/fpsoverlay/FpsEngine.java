package com.dsh.fpsoverlay;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 帧率统计引擎：轮询 SurfaceFlinger，把时间戳攒成一个滑动窗口，算出：
 *   - 瞬时 FPS（最近 1 秒的实际上屏帧率）
 *   - 平均帧时间（ms）
 *   - 1% low（最近 1 秒里最慢的 1% 帧对应的帧率）
 *
 * 轮询周期 400ms：--latency 每次都会返回最近 128 帧，即使中间隔了 400ms 也不会丢帧。
 */
public final class FpsEngine {

    public interface Listener {
        void onSample(Stats s);
    }

    public static final class Stats {
        public String message;   // 非 null 表示当前没有可用数据
        public String layer = "";
        public double fps;
        public double avgMs;
        public double lowFps;
        public double refreshHz;
        public int frames;
    }

    private static final long POLL_MS = 400L;
    private static final long WINDOW_NS = 1000000000L;
    private static final long KEEP_NS = 2000000000L;
    private static final long STALE_MS = 2000L;

    private final String pkgSelf;
    private final String manualLayer;
    private final android.os.PowerManager power;

    private final ArrayDeque<Long> stamps = new ArrayDeque<Long>();
    private long lastStamp = 0L;
    private long lastNewWall = 0L;
    private long refreshNs = 0L;
    private String layer = null;
    private String curPkg = "";

    private volatile boolean running = false;
    private Thread thread;

    public FpsEngine(Context ctx, String manualLayer) {
        this.pkgSelf = ctx.getPackageName();
        this.manualLayer = manualLayer == null ? "" : manualLayer;
        this.power = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
    }

    public void start(final Listener listener) {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(listener);
            }
        }, "fps-engine");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void loop(Listener listener) {
        // 1. 等 root 授权（用户可能正在点授权框）
        while (running && !RootShell.checkRoot()) {
            Stats s = new Stats();
            s.message = "未获得 root 权限，请在 Magisk / KernelSU 中允许本应用";
            listener.onSample(s);
            sleepQuietly(3000L);
        }
        if (!running) {
            return;
        }

        long lastResolve = 0L;
        while (running) {
            // 屏幕关闭时没必要继续跑 dumpsys，省电
            if (power != null && !power.isInteractive()) {
                Stats off = new Stats();
                off.message = "屏幕已关闭，暂停采样";
                listener.onSample(off);
                sleepQuietly(1500L);
                continue;
            }
            long t0 = SystemClock.elapsedRealtime();
            Stats s = new Stats();
            try {
                long now = SystemClock.elapsedRealtime();
                boolean stale = lastNewWall == 0L || (now - lastNewWall) > STALE_MS;
                boolean needResolve = (layer == null) || stale;

                if (needResolve && (now - lastResolve) > 2000L) {
                    lastResolve = now;
                    String prev = layer;
                    String picked = pickLayer();
                    if (picked != null && !picked.equals(prev)) {
                        layer = picked;
                        stamps.clear();
                        lastStamp = 0L;
                        lastNewWall = 0L;
                        refreshNs = 0L;
                    }
                }

                if (layer == null) {
                    s.message = "没有找到游戏图层，请先进入游戏再看";
                } else {
                    SurfaceFlinger.Latency lat = SurfaceFlinger.readLatency(layer);
                    if (lat != null) {
                        if (lat.refreshNs > 0L) {
                            refreshNs = lat.refreshNs;
                        }
                        if (addNew(lat.stamps) > 0) {
                            lastNewWall = SystemClock.elapsedRealtime();
                        }
                    }
                    s = snapshot(layer);
                }
            } catch (Throwable e) {
                s = new Stats();
                s.message = "读取异常：" + e.getClass().getSimpleName();
            }
            listener.onSample(s);

            long spent = SystemClock.elapsedRealtime() - t0;
            long sleep = POLL_MS - spent;
            if (sleep < 80L) {
                sleep = 80L;
            }
            sleepQuietly(sleep);
        }
    }

    private String pickLayer() {
        if (manualLayer.length() > 0) {
            return manualLayer;
        }
        String pkg = SurfaceFlinger.foregroundPackage();
        if (pkg == null) {
            return layer; // 判断不出来就先沿用上一次的
        }
        if (pkg.equals(pkgSelf)) {
            return layer; // 用户正停在设置页，保持原图层
        }
        String home = SurfaceFlinger.homePackage();
        if (home != null && pkg.equals(home)) {
            return layer; // 桌面，不算游戏
        }
        if (!pkg.equals(curPkg)) {
            // 切换了应用，原来的图层作废，重新识别
            curPkg = pkg;
            layer = null;
        }
        if (layer == null) {
            layer = SurfaceFlinger.resolve(pkg);
        }
        return layer;
    }

    private int addNew(List<Long> list) {
        int added = 0;
        for (int i = 0; i < list.size(); i++) {
            long v = list.get(i);
            if (v > lastStamp) {
                stamps.addLast(v);
                lastStamp = v;
                added++;
            }
        }
        while (!stamps.isEmpty() && (lastStamp - stamps.peekFirst()) > KEEP_NS) {
            stamps.pollFirst();
        }
        return added;
    }

    private Stats snapshot(String layerName) {
        Stats s = new Stats();
        s.layer = layerName;
        if (refreshNs > 0L) {
            s.refreshHz = 1e9 / refreshNs;
        }
        if (stamps.size() < 2) {
            s.message = "等待画面数据…";
            return s;
        }

        long from = lastStamp - WINDOW_NS;
        ArrayList<Long> win = new ArrayList<Long>();
        for (Long v : stamps) {
            if (v >= from) {
                win.add(v);
            }
        }
        s.frames = win.size();
        if (win.size() < 2) {
            s.message = "等待画面数据…";
            return s;
        }

        long first = win.get(0);
        long last = win.get(win.size() - 1);
        long span = last - first;
        if (span <= 0L) {
            s.message = "等待画面数据…";
            return s;
        }
        s.fps = (win.size() - 1) * 1e9 / span;

        int n = win.size() - 1;
        ArrayList<Double> deltas = new ArrayList<Double>(n);
        double sum = 0;
        for (int i = 1; i <= n; i++) {
            double d = (win.get(i) - win.get(i - 1)) / 1e6;
            if (d > 0) {
                deltas.add(d);
                sum += d;
            }
        }
        if (!deltas.isEmpty()) {
            s.avgMs = sum / deltas.size();
            Collections.sort(deltas);
            int idx = (int) Math.floor(deltas.size() * 0.99);
            if (idx >= deltas.size()) {
                idx = deltas.size() - 1;
            }
            double p99 = deltas.get(idx);
            s.lowFps = p99 > 0 ? 1000.0 / p99 : 0;
        }

        long idle = SystemClock.elapsedRealtime() - lastNewWall;
        if (lastNewWall > 0L && idle > 800L) {
            s.message = "画面没有新帧（可能已暂停 / 切到后台）";
        }
        return s;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
