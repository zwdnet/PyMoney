package com.pymoney;

import android.content.Context;
import android.content.SharedPreferences;

/** 一点点设置：自动备份开关、上次备份时间、图表页上次选的图、界面明暗。 */
public final class Prefs {

    private static final String NAME = "pymoney";

    /** 外观：跟随系统（默认） */
    public static final int THEME_AUTO = 0;
    /** 外观：固定浅色 */
    public static final int THEME_LIGHT = 1;
    /** 外观：固定深色 */
    public static final int THEME_DARK = 2;

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean autoBackup() {
        return prefs.getBoolean("autoBackup", true);
    }

    public void setAutoBackup(boolean on) {
        prefs.edit().putBoolean("autoBackup", on).apply();
    }

    public long lastBackupAt() {
        return prefs.getLong("lastBackupAt", 0L);
    }

    public void setLastBackupAt(long time) {
        prefs.edit().putLong("lastBackupAt", time).apply();
    }

    public String lastBackupError() {
        return prefs.getString("lastBackupError", null);
    }

    public void setLastBackupError(String error) {
        prefs.edit().putString("lastBackupError", error).apply();
    }

    public int chartKind() {
        return prefs.getInt("chartKind", 0);
    }

    public void setChartKind(int index) {
        prefs.edit().putInt("chartKind", index).apply();
    }

    // ---------- 外观 ----------

    /** 0=跟随系统，1=浅色，2=深色 */
    public int themeMode() {
        return prefs.getInt("themeMode", THEME_AUTO);
    }

    public void setThemeMode(int mode) {
        prefs.edit().putInt("themeMode", mode).apply();
    }

    // ---------- 备份位置 ----------

    /** 0=Download/PyMoney，1=自选目录（记住），2=每次问我 */
    public int backupTargetMode() {
        return prefs.getInt("backupTargetMode", BackupTarget.MODE_MEDIA);
    }

    public void setBackupTargetMode(int mode) {
        prefs.edit().putInt("backupTargetMode", mode).apply();
    }

    /** 自选目录那棵树的 URI，只在 mode=1 时用 */
    public String backupTreeUri() {
        return prefs.getString("backupTreeUri", null);
    }

    public void setBackupTreeUri(String uri) {
        prefs.edit().putString("backupTreeUri", uri).apply();
    }

    /** 给人看的目录名，选目录时记一次，之后不必再去问 provider */
    public String backupDirLabel() {
        return prefs.getString("backupDirLabel", null);
    }

    public void setBackupDirLabel(String label) {
        prefs.edit().putString("backupDirLabel", label).apply();
    }

    // ---------- 备份历史 ----------

    /** 最近若干条备份记录，格式见 core.BackupLog */
    public String backupHistory() {
        return prefs.getString("backupHistory", "");
    }

    public void setBackupHistory(String history) {
        prefs.edit().putString("backupHistory", history).apply();
    }
}
