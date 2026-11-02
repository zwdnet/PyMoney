package com.pymoney.ui;

import android.app.UiModeManager;
import android.content.Context;
import android.os.Build;

import com.pymoney.Prefs;

/**
 * 界面明暗。真正决定配色的是系统给 App 的 uiMode：values/ 与 values-night/ 两套资源由系统挑，
 * 这里只负责在 Android 12+ 上把「只改这个 App」的那个开关拨到用户选的值。
 */
public final class Theme {

    private Theme() {
    }

    /** 必须在任何 Activity 创建之前调用（PyMoneyApp.onCreate 里跑一次） */
    public static void apply(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            // Android 12 以前没有「单独给一个 App 指定明暗」的公开接口，
            // 只能跟随系统；values-night/ 已经能跟着系统自动换。
            return;
        }
        UiModeManager manager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (manager == null) {
            return;
        }
        int mode;
        switch (new Prefs(context).themeMode()) {
            case Prefs.THEME_LIGHT:
                mode = UiModeManager.MODE_NIGHT_NO;
                break;
            case Prefs.THEME_DARK:
                mode = UiModeManager.MODE_NIGHT_YES;
                break;
            default:
                mode = UiModeManager.MODE_NIGHT_AUTO;
                break;
        }
        try {
            manager.setApplicationNightMode(mode);
        } catch (Throwable ignored) {
            // 个别 ROM 把这个接口改成空实现或者直接抛；界面回落到跟随系统，记账功能不受影响
        }
    }

    public static boolean canForce() {
        return Build.VERSION.SDK_INT >= 31;
    }
}
