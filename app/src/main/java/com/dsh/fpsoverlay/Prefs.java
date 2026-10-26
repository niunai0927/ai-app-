package com.dsh.fpsoverlay;

import android.content.Context;
import android.content.SharedPreferences;

/** 极简设置存储：位置、大小、是否显示曲线、手动指定的图层名。 */
public final class Prefs {

    private static final String NAME = "fps_overlay_prefs";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_GRAPH = "graph";
    private static final String KEY_SIZE = "size";
    private static final String KEY_LAYER = "layer";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public int x() {
        return sp.getInt(KEY_X, 24);
    }

    public int y() {
        return sp.getInt(KEY_Y, 140);
    }

    public void setPos(int x, int y) {
        sp.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply();
    }

    public boolean graph() {
        return sp.getBoolean(KEY_GRAPH, true);
    }

    public void setGraph(boolean g) {
        sp.edit().putBoolean(KEY_GRAPH, g).apply();
    }

    /** 0~80，默认 40 表示 100% 大小。 */
    public int sizeProgress() {
        return sp.getInt(KEY_SIZE, 25);
    }

    public void setSizeProgress(int p) {
        if (p < 0) {
            p = 0;
        }
        if (p > 80) {
            p = 80;
        }
        sp.edit().putInt(KEY_SIZE, p).apply();
    }

    public float scale() {
        // 0 -> 0.75 倍，40 -> 1.15 倍，80 -> 1.55 倍
        return 0.75f + sizeProgress() * 0.01f;
    }

    public String layer() {
        String s = sp.getString(KEY_LAYER, "");
        return s == null ? "" : s;
    }

    public void setLayer(String layer) {
        sp.edit().putString(KEY_LAYER, layer == null ? "" : layer).apply();
    }
}
