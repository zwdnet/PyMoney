package com.pymoney.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.pymoney.BackupManager;
import com.pymoney.BackupTarget;
import com.pymoney.Prefs;
import com.pymoney.PlainCopy;
import com.pymoney.R;
import com.pymoney.core.BackupLog;
import com.pymoney.core.BackupRecord;
import com.pymoney.core.CryptoState;
import com.pymoney.core.KeyEnvelope;
import com.pymoney.db.AppDb;
import com.pymoney.db.Migrator;

import java.io.File;
import java.security.SecureRandom;

/**
 * 安全与加密。
 *
 * 开启加密要过三道：先有一次通过回读校验的备份 → 设口令（抄不抄恢复码必须明确二选一）→
 * 说清楚 Termux 那边从此读不了。口令和恢复码都只在内存里过一次、以任何形式都不落盘，
 * 这个页面是唯一能改它们的地方，所以它不能像其它页那样被 LockGate 挡在门外——
 * 锁着的时候也得进得来，否则连「输口令」这一件事都没处做。
 *
 * 算 PBKDF2（约半秒）和改一万两千行都放后台线程，前台只留一个不可取消的进度框；
 * 口令作为参数一路传进那个线程，不停在任何字段里。
 */
public class SecurityActivity extends Activity {

    private static final int MIN_PASS = 6;
    /** 恢复码的字符表去掉 0/O/1/I/L，抄在纸上不会认错 */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** 后台要跑的活儿：progress 给迁移用，口令这类秘密由闭包自己带着 */
    private interface Job {
        String run(Migrator.Progress progress);
    }

    private AppDb db;
    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy;

    private TextView stateView;
    private TextView detailView;
    private TextView copyView;
    private View passCard;
    private Button enableButton;
    private Button resumeButton;
    private Button disableButton;
    private Button unlockButton;
    private Button lockButton;
    private Button changeButton;
    private Button recoveryButton;
    private Button copyExportButton;
    private Button copyDeleteButton;

    private static final int REQ_SAVE_AS = 2001;
    /** 等用户从「另存为」回来之后要接着做的事：备份成功后是否继续开加密 */
    private boolean resumeEnableAfterBackup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security);
        db = AppDb.get(this);
        prefs = new Prefs(this);
        stateView = (TextView) findViewById(R.id.sec_state);
        detailView = (TextView) findViewById(R.id.sec_detail);
        copyView = (TextView) findViewById(R.id.sec_copy_state);
        passCard = findViewById(R.id.sec_pass_card);
        enableButton = (Button) findViewById(R.id.sec_enable);
        resumeButton = (Button) findViewById(R.id.sec_resume);
        disableButton = (Button) findViewById(R.id.sec_disable);
        unlockButton = (Button) findViewById(R.id.sec_unlock);
        lockButton = (Button) findViewById(R.id.sec_lock);
        changeButton = (Button) findViewById(R.id.sec_change);
        recoveryButton = (Button) findViewById(R.id.sec_recovery);
        copyExportButton = (Button) findViewById(R.id.sec_copy_export);
        copyDeleteButton = (Button) findViewById(R.id.sec_copy_delete);

        click(enableButton, new Runnable() {
            @Override
            public void run() {
                startEnable();
            }
        });
        click(resumeButton, new Runnable() {
            @Override
            public void run() {
                migrate(null, null, true);
            }
        });
        click(disableButton, new Runnable() {
            @Override
            public void run() {
                startDisable();
            }
        });
        click(unlockButton, new Runnable() {
            @Override
            public void run() {
                LockGate.ensure(SecurityActivity.this, new LockGate.Ready() {
                    @Override
                    public void onReady() {
                        refresh();
                    }
                });
            }
        });
        click(lockButton, new Runnable() {
            @Override
            public void run() {
                db.crypto().lock();
                Ui.toast(SecurityActivity.this, getString(R.string.sec_locked_now));
                refresh();
            }
        });
        click(changeButton, new Runnable() {
            @Override
            public void run() {
                askChangePass();
            }
        });
        click(recoveryButton, new Runnable() {
            @Override
            public void run() {
                askRecoveryForExisting();
            }
        });
        click(copyExportButton, new Runnable() {
            @Override
            public void run() {
                exportCopy();
            }
        });
        click(copyDeleteButton, new Runnable() {
            @Override
            public void run() {
                deleteCopy();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlainCopy.sweepExpired(this);
        refresh();
    }

    // ---------- 显示 ----------

    private void refresh() {
        CryptoState crypto = db.crypto();
        int[] pending = crypto.pending();
        int left = pending[0] + pending[1];
        if (!crypto.encrypted()) {
            stateView.setText(R.string.sec_plain);
            stateView.setTextColor(getColor(R.color.text_primary));
        } else if (!crypto.unlocked()) {
            stateView.setText(R.string.sec_locked);
            stateView.setTextColor(getColor(R.color.warning));
        } else {
            stateView.setText(R.string.sec_unlocked);
            stateView.setTextColor(getColor(R.color.income));
        }

        StringBuilder detail = new StringBuilder();
        detail.append(getString(R.string.sec_stats, crypto.migrated(), left,
                pending[0], pending[1]));
        detail.append('\n').append(getString(crypto.hasRecoveryCode()
                ? R.string.sec_recovery_set : R.string.sec_recovery_none));
        if (crypto.cursor() > 0) {
            detail.append('\n').append(getString(R.string.sec_mixed, crypto.cursor()));
        }
        detailView.setText(detail);

        boolean encrypted = crypto.encrypted();
        boolean unlocked = crypto.unlocked();
        enableButton.setVisibility(encrypted ? View.GONE : View.VISIBLE);
        disableButton.setVisibility(encrypted ? View.VISIBLE : View.GONE);
        disableButton.setEnabled(unlocked);
        resumeButton.setVisibility(crypto.cursor() > 0 && unlocked ? View.VISIBLE : View.GONE);
        resumeButton.setText(getString(R.string.sec_resume, left));
        // 明文状态下这四个必然都点不动——与其摆四个灰按钮，不如整块藏掉，
        // 需要设口令的地方就是上面那个「开启加密」，它自己会要口令。
        passCard.setVisibility(encrypted ? View.VISIBLE : View.GONE);
        unlockButton.setEnabled(encrypted && !unlocked);
        lockButton.setEnabled(encrypted && unlocked);
        changeButton.setEnabled(encrypted);
        recoveryButton.setEnabled(encrypted && unlocked);
        showCopy();
    }

    private void showCopy() {
        File copy = PlainCopy.file(this);
        String auto = getString(R.string.sec_copy_auto, PlainCopy.KEEP_DAYS);
        if (!copy.exists()) {
            copyView.setText(getString(R.string.sec_copy_none) + "\n" + auto);
            copyExportButton.setEnabled(false);
            copyDeleteButton.setEnabled(false);
            return;
        }
        int days = PlainCopy.ageDays(copy);
        String age = days == 0 ? getString(R.string.sec_copy_age_now)
                : getString(R.string.sec_copy_age_days, days);
        copyView.setText(getString(R.string.sec_copy_days, age, PlainCopy.sizeText(copy))
                + "\n" + auto);
        copyExportButton.setEnabled(true);
        copyDeleteButton.setEnabled(true);
    }

    // ---------- 开启加密：三道 ----------

    private void startEnable() {
        if (busy) {
            return;
        }
        if (BackupLog.hasVerified(prefs.backupHistory())) {
            askPassForEnable();
            return;
        }
        // 没有一份校验过的备份就别动那一万两千行：那是唯一能整体退回的地方
        Ui.confirm(this, getString(R.string.sec_enable), getString(R.string.sec_enable_need_backup),
                new Runnable() {
                    @Override
                    public void run() {
                        startBackup(true);
                    }
                });
    }

    /**
     * @param thenEnable 备份成功后是否接着走开启加密那两步
     */
    private void startBackup(final boolean thenEnable) {
        Ui.toast(this, getString(R.string.sec_backing_up,
                BackupTarget.create(prefs).describe(this)));
        try {
            BackupManager.get(this).backupNow(new BackupManager.Callback() {
                @Override
                public void onResult(BackupRecord record) {
                    if (record.ok) {
                        Ui.toast(SecurityActivity.this,
                                getString(R.string.backup_ok, record.location()));
                        if (thenEnable) {
                            askPassForEnable();
                        }
                        return;
                    }
                    Ui.toast(SecurityActivity.this,
                            getString(R.string.backup_failed, record.error));
                    offerSaveAs(thenEnable);
                }
            });
        } catch (Exception e) {
            // 这段是用户点完「确定」立刻跑的。在这儿抛出去就是他看到的「无任何提示闪回主界面」，
            // 所以哪怕只是把异常念出来也比让它把 Activity 打死强
            Ui.toast(this, getString(R.string.backup_failed, describe(e)));
        }
    }

    /**
     * 备份这条路走不通时给一条一定走得通的：系统「另存为」。
     * 用户导出 CSV 用的就是这条路，说明这台机器上它没问题；写完照样回读校验，
     * 校验通过的历史同样能满足「开启加密前要先有备份」那道门。
     */
    private void offerSaveAs(final boolean thenEnable) {
        BackupRecord last = BackupLog.latest(prefs.backupHistory());
        final String reason = last == null ? "未知" : String.valueOf(last.error);
        new AlertDialog.Builder(this)
                .setTitle(R.string.sec_backup_failed_title)
                .setMessage(getString(R.string.sec_backup_failed_ask, reason))
                .setNegativeButton(R.string.sec_backup_failed_no, null)
                .setPositiveButton(R.string.sec_backup_failed_yes,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                resumeEnableAfterBackup = thenEnable;
                                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                intent.addCategory(Intent.CATEGORY_OPENABLE);
                                intent.setType("application/octet-stream");
                                intent.putExtra(Intent.EXTRA_TITLE, AppDb.DB_NAME);
                                startActivityForResult(intent, REQ_SAVE_AS);
                            }
                        })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE_AS || resultCode != RESULT_OK || data == null) {
            return;
        }
        final boolean thenEnable = resumeEnableAfterBackup;
        resumeEnableAfterBackup = false;
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        Ui.toast(this, getString(R.string.sec_writing));
        BackupManager.get(this).backupToUri(uri, new BackupManager.Callback() {
            @Override
            public void onResult(BackupRecord record) {
                Ui.toast(SecurityActivity.this, getString(
                        record.ok ? R.string.backup_ok : R.string.backup_failed,
                        record.ok ? record.location() : record.error));
                if (record.ok && thenEnable) {
                    askPassForEnable();
                }
            }
        });
    }

    private void askPassForEnable() {
        Ui.password(this, getString(R.string.sec_enable), getString(R.string.sec_set_pass),
                new Ui.TextCallback() {
                    @Override
                    public void onText(final String pass) {
                        if (!checkPass(pass)) {
                            return;
                        }
                        Ui.password(SecurityActivity.this, getString(R.string.sec_enable),
                                getString(R.string.sec_new_pass_again), new Ui.TextCallback() {
                                    @Override
                                    public void onText(String again) {
                                        if (!pass.equals(again)) {
                                            Ui.toast(SecurityActivity.this,
                                                    getString(R.string.sec_pass_unmatched));
                                            return;
                                        }
                                        chooseRecovery(pass);
                                    }
                                });
                    }
                });
    }

    private boolean checkPass(String pass) {
        if (pass == null || pass.length() == 0) {
            Ui.toast(this, getString(R.string.sec_pass_empty));
            return false;
        }
        if (pass.length() < MIN_PASS) {
            Ui.toast(this, getString(R.string.sec_pass_short));
            return false;
        }
        return true;
    }

    /** 忘口令又没有恢复码＝数据永久不可读，所以这里必须让人明确二选一 */
    private void chooseRecovery(final String pass) {
        String[] labels = new String[]{getString(R.string.sec_recovery_choice_yes),
                getString(R.string.sec_recovery_choice_no)};
        Ui.choose(this, getString(R.string.sec_recovery_choice_title), labels,
                new Ui.TextCallback() {
                    @Override
                    public void onText(String index) {
                        if ("0".equals(index)) {
                            showCode(pass, newRecoveryCode());
                        } else if ("1".equals(index)) {
                            confirmEnable(pass, null);
                        }
                    }
                });
    }

    /** 恢复码只在屏幕上出现这一次，之后 App 再也看不到它 */
    private void showCode(final String pass, final String code) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sec_recovery_title)
                .setMessage(R.string.sec_recovery_body)
                .setView(selectable(code))
                .setPositiveButton(R.string.sec_recovery_close, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirmEnable(pass, code);
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Ui.toast(SecurityActivity.this, getString(R.string.sec_recovery_none));
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void confirmEnable(final String pass, final String recoveryCode) {
        Ui.confirm(this, getString(R.string.sec_enable), getString(R.string.sec_enable_confirm),
                new Runnable() {
                    @Override
                    public void run() {
                        migrate(pass, recoveryCode, false);
                    }
                });
    }

    // ---------- 迁移 ----------

    /** @param pass 非 null 表示「开启加密」：先装新信封再搬数据；null 表示接着上次游标往下搬 */
    private void migrate(final String pass, final String recoveryCode, final boolean resume) {
        if (busy) {
            return;
        }
        if (resume && !db.crypto().unlocked()) {
            Ui.toast(this, getString(R.string.sec_needs_unlock));
            return;
        }
        final File copy = PlainCopy.file(this);
        runBusy(getString(resume ? R.string.sec_resume : R.string.sec_enable),
                new Job() {
                    @Override
                    public String run(Migrator.Progress progress) {
                        CryptoState crypto = db.crypto();
                        if (pass != null) {
                            KeyEnvelope envelope = KeyEnvelope.newEnvelope(pass, recoveryCode);
                            if (!crypto.install(envelope, pass)) {
                                return getString(R.string.sec_op_failed);
                            }
                        }
                        return describeResult(new Migrator(db.sql(), crypto)
                                .toEncrypted(copy, resume, progress), false);
                    }
                });
    }

    private void startDisable() {
        if (!db.crypto().unlocked()) {
            Ui.toast(this, getString(R.string.sec_needs_unlock));
            return;
        }
        Ui.confirm(this, getString(R.string.sec_disable), getString(R.string.sec_disable_confirm),
                new Runnable() {
                    @Override
                    public void run() {
                        runBusy(getString(R.string.sec_disable), new Job() {
                            @Override
                            public String run(Migrator.Progress progress) {
                                return describeResult(new Migrator(db.sql(), db.crypto())
                                        .toPlain(progress), true);
                            }
                        });
                    }
                });
    }

    private String describeResult(Migrator.Result result, boolean plain) {
        if (result == null) {
            return getString(R.string.sec_migrate_failed, "没有结果");
        }
        if (!result.ok) {
            return getString(R.string.sec_migrate_failed, result.error);
        }
        return getString(plain ? R.string.sec_plain_done : R.string.sec_migrate_done,
                result.describe());
    }

    // ---------- 口令 / 恢复码 ----------

    private void askChangePass() {
        Ui.password(this, getString(R.string.sec_change), getString(R.string.sec_old_pass),
                new Ui.TextCallback() {
                    @Override
                    public void onText(final String oldPass) {
                        Ui.password(SecurityActivity.this, getString(R.string.sec_change),
                                getString(R.string.sec_new_pass), new Ui.TextCallback() {
                                    @Override
                                    public void onText(final String newPass) {
                                        if (!checkPass(newPass)) {
                                            return;
                                        }
                                        Ui.password(SecurityActivity.this,
                                                getString(R.string.sec_change),
                                                getString(R.string.sec_new_pass_again),
                                                new Ui.TextCallback() {
                                                    @Override
                                                    public void onText(String again) {
                                                        if (!newPass.equals(again)) {
                                                            Ui.toast(SecurityActivity.this,
                                                                    getString(R.string.sec_pass_unmatched));
                                                            return;
                                                        }
                                                        runBusy(getString(R.string.sec_change),
                                                                new Job() {
                                                                    @Override
                                                                    public String run(Migrator.Progress p) {
                                                                        boolean ok = db.crypto()
                                                                                .changePassphrase(oldPass, newPass);
                                                                        return getString(ok
                                                                                ? R.string.sec_pass_changed
                                                                                : R.string.sec_pass_wrong);
                                                                    }
                                                                });
                                                    }
                                                });
                                    }
                                });
                    }
                });
    }

    private void askRecoveryForExisting() {
        Ui.password(this, getString(R.string.sec_recovery), getString(R.string.sec_old_pass),
                new Ui.TextCallback() {
                    @Override
                    public void onText(final String pass) {
                        final String code = newRecoveryCode();
                        // 恢复码先给看、口令后验：解不开口令就什么都不改，这张纸也就没写进库
                        new AlertDialog.Builder(SecurityActivity.this)
                                .setTitle(R.string.sec_recovery_title)
                                .setMessage(R.string.sec_recovery_body)
                                .setView(selectable(code))
                                .setPositiveButton(R.string.sec_recovery_close,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                runBusy(getString(R.string.sec_recovery), new Job() {
                                                    @Override
                                                    public String run(Migrator.Progress progress) {
                                                        boolean ok = db.crypto()
                                                                .setRecoveryCode(pass, code);
                                                        return getString(ok
                                                                ? R.string.sec_recovery_saved
                                                                : R.string.sec_pass_wrong);
                                                    }
                                                });
                                            }
                                        })
                                .setNegativeButton(R.string.cancel, null)
                                .setCancelable(false)
                                .show();
                    }
                });
    }

    // ---------- 明文副本 ----------

    private void exportCopy() {
        final File copy = PlainCopy.file(this);
        if (!copy.exists()) {
            Ui.toast(this, getString(R.string.sec_copy_missing));
            return;
        }
        Ui.confirm(this, getString(R.string.sec_copy_export),
                getString(R.string.export_plain_db_confirm), new Runnable() {
                    @Override
                    public void run() {
                        BackupManager.get(SecurityActivity.this).exportCopy(copy,
                                new BackupManager.Callback() {
                                    @Override
                                    public void onResult(BackupRecord record) {
                                        String message = getString(record.ok
                                                ? R.string.backup_ok : R.string.backup_failed,
                                                record.ok ? record.location() : record.error);
                                        Ui.toast(SecurityActivity.this, message);
                                    }
                                });
                    }
                });
    }

    private void deleteCopy() {
        final File copy = PlainCopy.file(this);
        if (!copy.exists()) {
            Ui.toast(this, getString(R.string.sec_copy_missing));
            return;
        }
        Ui.confirm(this, getString(R.string.sec_copy_delete),
                getString(R.string.sec_copy_auto, PlainCopy.KEEP_DAYS), new Runnable() {
                    @Override
                    public void run() {
                        boolean gone = PlainCopy.delete(copy);
                        String message = getString(
                                gone ? R.string.sec_copy_deleted : R.string.sec_copy_missing);
                        Ui.toast(SecurityActivity.this, message);
                        showCopy();
                    }
                });
    }

    // ---------- 后台跑一条活儿 ----------

    /**
     * 进度框在主线程建、也在主线程关（ProgressDialog 认自己的 Looper），
     * 活儿本身在另一条线程；迁移每批结束回调一次，回调里只往主线程 post 数字。
     */
    private void runBusy(final String title, final Job job) {
        if (busy) {
            return;
        }
        busy = true;
        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setCancelable(false);
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setMax(1);
        dialog.setTitle(title);
        dialog.show();
        final Migrator.Progress reporter = new Migrator.Progress() {
            @Override
            public void onProgress(final int done, final int total) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        dialog.setMax(Math.max(1, total));
                        dialog.setProgress(done);
                    }
                });
            }
        };
        new Thread(new Runnable() {
            @Override
            public void run() {
                String outcome;
                try {
                    outcome = job.run(reporter);
                } catch (Exception e) {
                    outcome = getString(R.string.sec_migrate_failed, describe(e));
                }
                final String message = outcome;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        if (isFinishing()) {
                            return;
                        }
                        refresh();
                        if (message != null) {
                            Ui.toast(SecurityActivity.this, message);
                        }
                    }
                });
            }
        }).start();
    }

    private TextView selectable(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setGravity(Gravity.CENTER);
        int padding = Ui.dp(this, 20);
        view.setPadding(padding, padding, padding, padding);
        return view;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** 恢复码：20 个有效字符，分四组好抄 */
    private static String newRecoveryCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder text = new StringBuilder();
        for (int group = 0; group < 4; group++) {
            if (group > 0) {
                text.append('-');
            }
            for (int i = 0; i < 5; i++) {
                text.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
        }
        return text.toString();
    }

    private void click(Button button, final Runnable action) {
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
    }
}
