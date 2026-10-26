package com.dsh.fpsoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/**
 * 设置界面：授权、开关悬浮窗、调大小、图层诊断。
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATION = 100;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    private TextView tvStatus;
    private TextView tvSize;
    private Switch swGraph;
    private SeekBar sbSize;
    private Button btnToggle;

    private boolean rootChecking = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, 700L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvSize = (TextView) findViewById(R.id.tvSize);
        swGraph = (Switch) findViewById(R.id.swGraph);
        sbSize = (SeekBar) findViewById(R.id.sbSize);
        btnToggle = (Button) findViewById(R.id.btnToggle);
        Button btnDiag = (Button) findViewById(R.id.btnDiag);

        swGraph.setChecked(prefs.graph());
        sbSize.setProgress(prefs.sizeProgress());
        tvSize.setText("悬浮窗大小：" + Math.round(prefs.scale() * 100) + "%");

        swGraph.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setGraph(isChecked);
                applyToService();
            }
        });

        sbSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.setSizeProgress(progress);
                tvSize.setText("悬浮窗大小：" + Math.round(prefs.scale() * 100) + "%");
                if (fromUser) {
                    applyToService();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleService();
            }
        });

        btnDiag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLayerPicker();
            }
        });

        ensureRootChecked(false);
    }

    private void applyToService() {
        OverlayService svc = OverlayService.INSTANCE;
        if (svc != null) {
            svc.applyPrefs();
        }
    }

    private void toggleService() {
        if (OverlayService.running) {
            startService(new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_STOP));
        } else {
            if (!Settings.canDrawOverlays(this)) {
                openOverlaySettings();
                return;
            }
            askNotificationPermission();
            try {
                startForegroundService(new Intent(this, OverlayService.class));
            } catch (Throwable e) {
                tvStatus.setText("启动失败：" + e.getClass().getSimpleName());
            }
        }
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 300L);
    }

    private void openOverlaySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATION);
        }
    }

    private void ensureRootChecked(boolean force) {
        if (rootChecking) {
            return;
        }
        if (!force && RootShell.rootState() != RootShell.UNKNOWN) {
            return;
        }
        rootChecking = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                RootShell.checkRoot();
                rootChecking = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                    }
                });
            }
        }, "root-check").start();
    }

    private void refreshStatus() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        String overlay = overlayOk ? "悬浮窗权限 ✓" : "悬浮窗权限 ✗";
        String root;
        int rs = RootShell.rootState();
        if (rs == RootShell.HAS_ROOT) {
            root = "root ✓";
        } else if (rs == RootShell.NO_ROOT) {
            root = "root ✗";
        } else {
            root = "root 检测中…";
        }
        String line2 = OverlayService.running
                ? ("运行中 · " + OverlayService.statusText)
                : "悬浮窗未开启";
        tvStatus.setText(overlay + "    " + root + "\n" + line2);
        btnToggle.setText(OverlayService.running ? "关闭悬浮窗" : "开启悬浮窗");
    }

    // ------------------------------------------------------------- 图层诊断

    private void showLayerPicker() {
        tvStatus.setText("正在读取图层列表…");
        ensureRootChecked(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> layers = SurfaceFlinger.listLayers();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        refreshStatus();
                        if (layers.isEmpty()) {
                            showTip("读取失败", "没有拿到图层列表。请确认已在 Magisk / KernelSU 中允许本应用使用 root，"
                                    + "并点一次“开启悬浮窗”触发授权。");
                            return;
                        }
                        final String[] items = new String[layers.size() + 1];
                        items[0] = "自动识别（推荐）";
                        for (int i = 0; i < layers.size(); i++) {
                            items[i + 1] = layers.get(i);
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("选择要监控的图层")
                                .setItems(items, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        if (which == 0) {
                                            prefs.setLayer("");
                                        } else {
                                            prefs.setLayer(items[which]);
                                        }
                                        OverlayService svc = OverlayService.INSTANCE;
                                        if (svc != null) {
                                            svc.restartEngine();
                                        }
                                        tvStatus.setText(which == 0
                                                ? "已切换为自动识别图层"
                                                : "已锁定图层：" + items[which]);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        }, "layer-list").start();
    }

    private void showTip(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ------------------------------------------------------------- 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        super.onDestroy();
    }
}
