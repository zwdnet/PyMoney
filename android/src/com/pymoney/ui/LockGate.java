package com.pymoney.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.pymoney.db.AppDb;

/**
 * 需要看到明文的页面进门前的那道锁。
 *
 * 明文库和已解锁的库直接放行；库已加密但还没输口令，就弹口令框。
 * 口令只在内存里过一次，不写 SharedPreferences、不写日志、不进任何历史。
 * 首页和桌面小组件只有数字，不需要明文，所以不套这个门。
 */
public final class LockGate {

    /** 解锁完成（或者本来就不需要解锁）之后要做的事 */
    public interface Ready {
        void onReady();
    }

    private LockGate() {
    }

    /** 只要「解开」，不接力做什么（首页进菜单前、数据页导出前用这个） */
    public static void ensure(Activity activity) {
        ensure(activity, null);
    }

    /**
     * 取消 = 放弃这次操作、关掉对话框，**不动当前页面**。
     * 这个对话框通常挂在首页上（点菜单格子先过锁），所以这里绝不能 finish——
     * 把首页关掉就等于退出 App。取消后应当还停在首页，可以再点一次。
     */
    public static void ensure(final Activity activity, final Ready ready) {
        gate(activity, ready, false);
    }

    /** 临时页面（桌面小组件拉起来的记一笔）才用：取消直接关掉这一页，回桌面 */
    public static void ensureOrFinish(final Activity activity, final Ready ready) {
        gate(activity, ready, true);
    }

    private static void gate(final Activity activity, final Ready ready,
                             final boolean finishOnCancel) {
        if (AppDb.get(activity).crypto().unlocked()) {
            if (ready != null) {
                ready.onReady();
            }
            return;
        }
        Runnable onCancel = null;
        if (finishOnCancel) {
            onCancel = new Runnable() {
                @Override
                public void run() {
                    activity.finish();
                }
            };
        }
        ask(activity, ready, false, null, onCancel);
    }

    private static void ask(final Activity activity, final Ready ready,
                            final boolean recovery, final String error, final Runnable onCancel) {
        final EditText input = new EditText(activity);
        input.setHint(recovery ? "恢复码" : "记账口令");
        CheckBox show = Ui.maskToggle(activity, input);
        int padding = Ui.dp(activity, 20);
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(padding, padding / 2, padding, 0);
        wrapper.addView(input);
        wrapper.addView(show);
        String message = error != null ? error
                : (recovery ? "输入抄下来的恢复码解开账本。"
                        : "这个账本已经加密，输入口令才能看到事项名和类别名。");
        new AlertDialog.Builder(activity)
                .setTitle(recovery ? "用恢复码解锁" : "输入口令")
                .setMessage(message)
                .setView(wrapper)
                .setPositiveButton("解锁", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String text = input.getText().toString();
                        // 空输入不往下走：PBKDF2 拿到空口令会抛异常，直接重开对话框跟「口令不对」一个手感
                        if (text.length() == 0) {
                            ask(activity, ready, recovery,
                                    recovery ? "还没输入恢复码，再试一次。" : "还没输入口令，再试一次。",
                                    onCancel);
                            return;
                        }
                        // 前后空白是不是口令的一部分，App 不能替用户猜：原样交给派生函数
                        boolean ok = recovery
                                ? AppDb.get(activity).crypto().unlockRecovery(text)
                                : AppDb.get(activity).crypto().unlock(text);
                        if (ok) {
                            if (ready != null) {
                                ready.onReady();
                            }
                        } else {
                            ask(activity, ready, recovery, "口令不对，再试一次。", onCancel);
                        }
                    }
                })
                .setNeutralButton(recovery ? "改用口令" : "用恢复码",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                ask(activity, ready, !recovery, null, onCancel);
                            }
                        })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 默认什么都不做：对话框关掉，人还在点击前的那个页面上
                        if (onCancel != null) {
                            onCancel.run();
                        }
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        // 系统返回键 / 点空白处也算「取消」，和按钮同一个语义
                        if (onCancel != null) {
                            onCancel.run();
                        }
                    }
                })
                .setCancelable(true)
                .show();
    }
}
