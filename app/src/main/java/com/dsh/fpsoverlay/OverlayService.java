package com.dsh.fpsoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/**
 * 前台服务：持有悬浮窗、驱动 FpsEngine、处理拖动与点击。
 */
public class OverlayService extends Service implements FpsEngine.Listener {

    public static final String ACTION_STOP = "com.dsh.fpsoverlay.action.STOP";
    public static final String CHANNEL_ID = "fps_overlay_channel";
    private static final int NOTIFY_ID = 1001;

    public static volatile boolean running = false;
    public static volatile String statusText = "已停止";
    public static volatile OverlayService INSTANCE = null;

    private WindowManager wm;
    private OverlayView view;
    private WindowManager.LayoutParams lp;
    private Prefs prefs;
    private FpsEngine engine;
    private Handler ui;
    private boolean started = false;

    @Override
    public void onCreate() {
        super.onCreate();
        ui = new Handler(Looper.getMainLooper());
        prefs = new Prefs(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        start();
        return START_STICKY;
    }

    private void start() {
        if (started) {
            return;
        }
        started = true;
        INSTANCE = this;

        // 先转前台，避免 startForegroundService 5 秒超时被系统杀掉
        startForeground(NOTIFY_ID, buildNotification());

        if (!Settings.canDrawOverlays(this)) {
            statusText = "缺少「显示在其他应用上层」权限";
            stopSelf();
            return;
        }

        running = true;
        statusText = "正在启动…";

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        view = new OverlayView(this);
        view.setScale(prefs.scale());
        view.setGraph(prefs.graph());

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.x();
        lp.y = prefs.y();

        view.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;
            private long downTime;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        downTime = SystemClock.uptimeMillis();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (!dragging && (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5))) {
                            dragging = true;
                        }
                        if (dragging) {
                            lp.x = startX + dx;
                            lp.y = startY + dy;
                            safeUpdateLayout();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging && (SystemClock.uptimeMillis() - downTime) < 400L) {
                            toggleGraph();
                        } else if (dragging) {
                            clampPosition();
                            prefs.setPos(lp.x, lp.y);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(view, lp);
        } catch (Throwable e) {
            statusText = "悬浮窗添加失败：" + e.getClass().getSimpleName();
            running = false;
            stopSelf();
            return;
        }

        restartEngine();
    }

    public void restartEngine() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        engine = new FpsEngine(getApplicationContext(), prefs.layer());
        engine.start(this);
    }

    @Override
    public void onSample(final FpsEngine.Stats s) {
        statusText = s.message != null
                ? s.message
                : String.format(Locale.US, "%.1f FPS  ·  %s", s.fps, shortLayer(s.layer));
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (view == null) {
                    return;
                }
                if (s.fps > 0) {
                    view.pushHistory(s.fps);
                }
                view.setStats(s);
            }
        });
    }

    private static String shortLayer(String layer) {
        if (layer == null) {
            return "";
        }
        return layer.length() > 34 ? "…" + layer.substring(layer.length() - 33) : layer;
    }

    private void toggleGraph() {
        boolean g = !prefs.graph();
        prefs.setGraph(g);
        applyPrefs();
    }

    /** 主界面改设置后实时生效。 */
    public void applyPrefs() {
        if (view == null || wm == null) {
            return;
        }
        view.setScale(prefs.scale());
        view.setGraph(prefs.graph());
        view.requestLayout();
        safeUpdateLayout();
    }

    private void safeUpdateLayout() {
        try {
            if (wm != null && view != null && lp != null) {
                wm.updateViewLayout(view, lp);
            }
        } catch (Throwable ignored) {
        }
    }

    private void clampPosition() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int vw = view != null ? view.getWidth() : 0;
        int vh = view != null ? view.getHeight() : 0;
        if (lp.x < 0) {
            lp.x = 0;
        }
        if (lp.y < 0) {
            lp.y = 0;
        }
        if (vw > 0 && lp.x + vw > sw) {
            lp.x = Math.max(0, sw - vw);
        }
        if (vh > 0 && lp.y + vh > sh) {
            lp.y = Math.max(0, sh - vh);
        }
        safeUpdateLayout();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------- 通知

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "帧率悬浮窗", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("帧率悬浮窗运行中")
                .setContentText("点击回到设置，或直接在这里关闭")
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭悬浮窗", stopPi)
                .setOngoing(true)
                .build();
    }

    // ------------------------------------------------------------- 生命周期

    @Override
    public void onDestroy() {
        running = false;
        INSTANCE = null;
        statusText = "已停止";
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        if (wm != null && view != null) {
            try {
                wm.removeView(view);
            } catch (Throwable ignored) {
            }
        }
        view = null;
        wm = null;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
