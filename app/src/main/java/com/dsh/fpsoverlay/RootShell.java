package com.dsh.fpsoverlay;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 通过 su 执行 shell 命令。
 *
 * 每次调用都会新起一个 su 进程（Magisk / KernelSU 会复用后台 daemon，开销很小），
 * 这样实现最简单，也不会出现常驻 shell 卡死难恢复的问题。
 * 单条命令的 stdout 末尾会附带 stderr（命令里追加了 2>&1），便于把错误直接显示到界面上。
 */
public final class RootShell {

    public static final int UNKNOWN = -1;
    public static final int NO_ROOT = 0;
    public static final int HAS_ROOT = 1;

    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static volatile int rootState = UNKNOWN;

    private RootShell() {
    }

    public static int rootState() {
        return rootState;
    }

    /** 首次调用会弹出 root 授权框，所以要给足超时时间。 */
    public static boolean checkRoot() {
        String out = run("id", 8000);
        boolean ok = out != null && out.contains("uid=0");
        rootState = ok ? HAS_ROOT : NO_ROOT;
        return ok;
    }

    /**
     * 同步执行一条命令并返回全部输出；失败/超时返回 null。
     * 必须在工作线程调用，不要放在主线程。
     */
    public static String run(String cmd, int timeoutMs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd + " 2>&1"});
            final Process target = p;
            Future<String> future = POOL.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return readAll(target.getInputStream());
                }
            });
            String out = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return out == null ? "" : out;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
