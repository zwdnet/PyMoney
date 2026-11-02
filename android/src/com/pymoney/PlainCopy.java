package com.pymoney;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/**
 * 迁移前落在 App 私有目录里的那份明文副本。
 *
 * 它是唯一一次「一条 SQL 改一万两千行」的退路——没有退路就是拿十几年的账赌 SQLite 不出错。
 * 也正因为它整份是明文，跟加密的目的对冲，所以只留 7 天：状态页能立刻删，删之前能先导出。
 * 放 filesDir 而不是共享目录，外部应用读不到，Termux 也读不到。
 */
public final class PlainCopy {

    public static final String FILE_NAME = "money.db.plain";
    public static final int KEEP_DAYS = 7;

    private static final long DAY_MS = 24L * 3600L * 1000L;

    private PlainCopy() {
    }

    public static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /** 多少天前生成的，不到一天算 0 */
    public static int ageDays(File file) {
        return (int) Math.max(0L, (System.currentTimeMillis() - file.lastModified()) / DAY_MS);
    }

    public static boolean expired(File file) {
        return file.exists() && ageDays(file) >= KEEP_DAYS;
    }

    /** 满 7 天就删。备份在 worker 线程上顺手调一次，不占主线程 */
    public static boolean sweepExpired(Context context) {
        File file = file(context);
        if (!expired(file)) {
            return false;
        }
        return delete(file);
    }

    public static boolean delete(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (file.delete()) {
            return true;
        }
        file.deleteOnExit();
        return false;
    }

    public static String sizeText(File file) {
        long bytes = file.length();
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
