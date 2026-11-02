package com.pymoney.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.TextView;

import com.pymoney.BackupManager;
import com.pymoney.BackupTarget;
import com.pymoney.Csv;
import com.pymoney.Prefs;
import com.pymoney.R;
import com.pymoney.Storage;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.core.BackupLog;
import com.pymoney.core.BackupRecord;
import com.pymoney.core.Sql;
import com.pymoney.db.AndroidSql;
import com.pymoney.db.AppDb;
import com.pymoney.db.Schema;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 数据导入导出。这一版重点解决「备份到底成没成功」：
 * 每次备份都是写完回读、打开、比指纹，界面显示的是回读出来的真实数字，
 * 位置也不再是字符串拼出来的假路径，可以选默认目录 / 自选目录 / 每次问我。
 */
public class ImportExportActivity extends Activity {

    private static final int REQ_DB = 1001;
    private static final int REQ_CSV_IN = 1002;
    private static final int REQ_PERM = 1003;
    private static final int REQ_TREE = 1004;
    private static final int REQ_CSV_OUT = 1005;
    private static final int REQ_BACKUP = 1006;
    private static final int REQ_PLAIN_SAVE = 1007;

    private AppDb db;
    private Prefs prefs;
    private TextView dbState;
    private TextView backupState;
    private TextView backupLocation;
    private TextView autoHint;
    private File pendingDb;
    private File pendingCsv;
    private Runnable afterPermission;
    private String locationNote;
    /** 「另存为」兜底那一次要写的是不是明文（决定写什么内容、默认叫什么名字） */
    private boolean pendingPlain;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data);
        db = AppDb.get(this);
        prefs = new Prefs(this);
        dbState = (TextView) findViewById(R.id.db_state);
        backupState = (TextView) findViewById(R.id.backup_state);
        backupLocation = (TextView) findViewById(R.id.backup_location);
        autoHint = (TextView) findViewById(R.id.backup_auto_hint);

        findViewById(R.id.import_db).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                pick("*/*", new String[]{"application/x-sqlite3", "application/octet-stream"},
                        REQ_DB);
            }
        });
        findViewById(R.id.import_csv).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                pick("text/*", new String[]{"text/csv", "text/plain", "application/octet-stream"},
                        REQ_CSV_IN);
            }
        });
        findViewById(R.id.backup_now).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                backupNow();
            }
        });
        findViewById(R.id.export_csv).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                exportCsv();
            }
        });
        findViewById(R.id.export_plain_db)
                .setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        askPlainExport();
                    }
                });
        findViewById(R.id.change_location)
                .setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        chooseLocation();
                    }
                });

        CheckBox auto = (CheckBox) findViewById(R.id.auto_backup);
        auto.setChecked(prefs.autoBackup());
        auto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                prefs.setAutoBackup(checked);
                showLocation();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        showDbState();
        showBackupState();
        showLocation();
    }

    // ---------- 界面文字 ----------

    private void showDbState() {
        long count = db.records().count();
        if (count == 0) {
            dbState.setText(R.string.db_empty);
            return;
        }
        dbState.setText(getString(R.string.db_stats, count, db.types().all().size(),
                MoneyFormat.formatDate(db.records().earliestTime()),
                MoneyFormat.formatDate(db.records().latestTime()))
                + "\n" + getString(R.string.db_path, db.file().getAbsolutePath()));
    }

    /** 一行结果：时间、成没成、条数。失败时把原因带上，方便对照上面那行位置 */
    private void showBackupState() {
        List<BackupRecord> rows = BackupLog.parse(prefs.backupHistory());
        if (rows.isEmpty()) {
            backupState.setText(R.string.backup_never);
            return;
        }
        BackupRecord latest = rows.get(0);
        backupState.setText(latest.ok
                ? getString(R.string.backup_last_ok, time(latest.at), latest.count)
                : getString(R.string.backup_last_failed, time(latest.at), latest.error));
    }

    private void showLocation() {
        BackupTarget target = BackupTarget.create(prefs);
        String described = target.describe(this);
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.backup_target_set, described));
        if (!target.available(this)) {
            text.append('\n').append(getString(R.string.backup_target_lost));
        }
        if (locationNote != null) {
            // 授权拿不到、保存框返回空这种失败只弹 toast 是看不见的，必须留在屏幕上
            text.append('\n').append(locationNote);
        }
        backupLocation.setText(text);
        if (prefs.autoBackup() && !target.unattended()) {
            autoHint.setText(R.string.backup_no_auto_support);
        } else {
            autoHint.setText("");
        }
    }

    private static String time(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }

    // ---------- 备份位置 ----------

    private void chooseLocation() {
        final boolean tree = BackupTarget.supportsTree();
        final String[] labels = tree
                ? new String[]{getString(R.string.backup_target_media),
                getString(R.string.backup_target_tree),
                getString(R.string.backup_target_single)}
                : new String[]{getString(R.string.backup_target_media),
                getString(R.string.backup_target_single)};
        Ui.choose(this, getString(R.string.backup_target_chooser), labels,
                new Ui.TextCallback() {
                    @Override
                    public void onText(String value) {
                        int index;
                        try {
                            index = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            return;
                        }
                        int mode = tree ? index
                                : (index == 0 ? BackupTarget.MODE_MEDIA : BackupTarget.MODE_SINGLE);
                        if (mode == BackupTarget.MODE_TREE) {
                            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                                    REQ_TREE);
                            return;
                        }
                        prefs.setBackupTargetMode(mode);
                        if (mode != BackupTarget.MODE_TREE) {
                            // 目录名是跟着树 URI 走的，换了模式再留着就会指鹿为马
                            prefs.setBackupTreeUri(null);
                            prefs.setBackupDirLabel(null);
                        }
                        locationNote = null;
                        showLocation();
                        // 选完当场把生效后的位置念一遍，否则「点了没反应」和「还没点」看起来一样
                        Ui.toast(ImportExportActivity.this,
                                getString(R.string.backup_target_set,
                                        BackupTarget.create(prefs)
                                                .describe(ImportExportActivity.this)));
                    }
                });
    }

    // ---------- 备份 ----------

    private void backupNow() {
        if (!BackupTarget.create(prefs).unattended()) {
            // 每次问我：先让系统保存框决定文件名和位置
            pendingPlain = false;
            startActivityForResult(saveAsIntent(AppDb.DB_NAME), REQ_BACKUP);
            return;
        }
        withWritePermission(new Runnable() {
            @Override
            public void run() {
                BackupManager.get(ImportExportActivity.this)
                        .backupNow(new BackupManager.Callback() {
                            @Override
                            public void onResult(BackupRecord record) {
                                report(record, false);
                            }
                        });
            }
        });
    }

    /**
     * 明文导出：位置也交给系统「另存为」自己定，和你导出 CSV 一样；
     * 必须先解锁——拿着锁着的账本导出去，写到的全是掩码。
     */
    private void askPlainExport() {
        if (!db.crypto().unlocked()) {
            Ui.toast(this, getString(R.string.err_locked_write));
            return;
        }
        Ui.confirm(this, getString(R.string.export_plain_db),
                getString(R.string.export_plain_db_confirm), new Runnable() {
                    @Override
                    public void run() {
                        startActivityForResult(saveAsIntent(BackupManager.PLAIN_NAME),
                                REQ_PLAIN_SAVE);
                    }
                });
    }

    private static Intent saveAsIntent(String name) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        return intent;
    }

    private void report(BackupRecord record, boolean plain) {
        String message = getString(record.ok ? R.string.backup_ok : R.string.backup_failed,
                record.ok ? record.location() : record.error);
        Ui.toast(this, message);
        showBackupState();
        showLocation();
        if (!record.ok) {
            offerSaveAs(plain);
        }
    }

    /**
     * 备份这条路走不通时给一条一定走得通的：系统「另存为」。
     * 这台机器上导出 CSV 用的就是这条路，说明它没问题；写完照样回读校验，
     * 校验通过的历史同样能满足「开启加密前要先有备份」那道门。
     * 刚才点的是「导出明文」，那这条退路也必须写明文——否则用户以为拿到能读开的库，实际是密文。
     */
    private void offerSaveAs(final boolean plain) {
        BackupRecord last = BackupLog.latest(prefs.backupHistory());
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.sec_backup_failed_title)
                .setMessage(getString(R.string.sec_backup_failed_ask,
                        last == null ? "未知" : String.valueOf(last.error)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.sec_backup_failed_yes,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                pendingPlain = plain;
                                startActivityForResult(
                                        saveAsIntent(plain
                                                ? BackupManager.PLAIN_NAME : AppDb.DB_NAME),
                                        REQ_BACKUP);
                            }
                        })
                .show();
    }

    // ---------- 结果处理 ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQ_TREE) {
                // 停在保存框却没选回来：不写回模式，位置保持原样，但要在屏幕上说清楚
                treeUnavailable(getString(R.string.backup_tree_no_data));
            }
            return;
        }
        try {
            if (requestCode == REQ_TREE) {
                adoptTree(data.getData());
                return;
            }
            Uri uri = data.getData();
            if (uri == null) {
                if (requestCode == REQ_BACKUP || requestCode == REQ_PLAIN_SAVE) {
                    Ui.toast(this, getString(R.string.backup_failed,
                            "系统没把选中位置的 uri 交回来"));
                }
                return;
            }
            if (requestCode == REQ_DB) {
                importDb(uri, Storage.displayName(this, uri));
            } else if (requestCode == REQ_CSV_IN) {
                importCsv(uri, Storage.displayName(this, uri));
            } else if (requestCode == REQ_CSV_OUT) {
                finishCsvExport(uri);
            } else if (requestCode == REQ_PLAIN_SAVE) {
                BackupManager.get(this).backupPlainToUri(uri, new BackupManager.Callback() {
                    @Override
                    public void onResult(BackupRecord record) {
                        report(record, true);
                    }
                });
            } else if (requestCode == REQ_BACKUP) {
                BackupManager manager = BackupManager.get(this);
                final boolean plain = pendingPlain;
                BackupManager.Callback callback = new BackupManager.Callback() {
                    @Override
                    public void onResult(BackupRecord record) {
                        report(record, plain);
                    }
                };
                if (plain) {
                    manager.backupPlainToUri(uri, callback);
                } else {
                    manager.backupToUri(uri, callback);
                }
            }
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.import_db_failed, String.valueOf(e.getMessage())));
        }
    }

    private void adoptTree(Uri treeUri) {
        if (treeUri == null) {
            treeUnavailable(getString(R.string.backup_tree_no_data));
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            treeUnavailable(getString(R.string.backup_tree_denied,
                    String.valueOf(e.getMessage())));
            return;
        }
        prefs.setBackupTargetMode(BackupTarget.MODE_TREE);
        prefs.setBackupTreeUri(treeUri.toString());
        // 存 tree 的标签而不是 describeUri：树 URI 里没有 "document" 段，
        // 走通用那条分支只能拿到一个 "primary:Download" 之类的怪串
        prefs.setBackupDirLabel(Storage.treeLabel(this, treeUri));
        locationNote = null;
        showLocation();
        // 选完当场把生效后的位置念一遍，否则「点了没反应」和「还没点」看起来一样
        Ui.toast(this, getString(R.string.backup_target_set,
                BackupTarget.create(prefs).describe(this)));
    }

    /**
     * 选文件夹这条路在这台机器上走不通（澎湃 OS 的文件管理器有可能既不给 uri，也不给持久授权）。
     * 只弹 toast 等于没说：屏幕上留下原因，同时给一个当场就能生效的替代——「每次都问我」，
     * 那条路就是导出 CSV 用的，这台机器上已经证明能通。
     */
    private void treeUnavailable(String reason) {
        locationNote = reason;
        showLocation();
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.backup_target_chooser)
                .setMessage(reason)
                .setNegativeButton(R.string.backup_tree_giveup, null)
                .setNeutralButton(R.string.backup_tree_try_again,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                startActivityForResult(
                                        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
                            }
                        })
                .setPositiveButton(R.string.backup_tree_try_single,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                prefs.setBackupTargetMode(BackupTarget.MODE_SINGLE);
                                prefs.setBackupTreeUri(null);
                                prefs.setBackupDirLabel(null);
                                locationNote = null;
                                showLocation();
                                Ui.toast(ImportExportActivity.this,
                                        getString(R.string.backup_target_set,
                                                BackupTarget.create(prefs)
                                                        .describe(ImportExportActivity.this)));
                            }
                        })
                .show();
    }

    // ---------- 导入 money.db ----------

    private void pick(String mime, String[] extraMimes, int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, extraMimes);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, request);
    }

    private void importDb(Uri uri, String name) throws Exception {
        final File temp = Storage.readToCache(this, uri, "import-" + name);
        SQLiteDatabase check = null;
        try {
            check = SQLiteDatabase.openDatabase(temp.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            Sql sql = new AndroidSql(check);
            if (!Schema.looksLikeMoneyDb(sql)) {
                Ui.toast(this, getString(R.string.import_db_not_moneydb));
                return;
            }
        } finally {
            if (check != null) {
                check.close();
            }
        }
        pendingDb = temp;
        Ui.confirm(this, getString(R.string.import_db),
                getString(R.string.import_db_confirm), new Runnable() {
                    @Override
                    public void run() {
                        replaceDatabase(pendingDb);
                    }
                });
    }

    private void replaceDatabase(File source) {
        try {
            File target = db.file();
            db.close();
            File backup = new File(target.getParentFile(), AppDb.DB_NAME + ".preimport");
            if (target.exists()) {
                Storage.copy(target, backup);
            }
            deleteSidecar(target, "-wal");
            deleteSidecar(target, "-shm");
            deleteSidecar(target, "-journal");
            Storage.copy(source, target);
            db.reload();
            long count = db.records().count();
            Ui.toast(this, getString(R.string.import_db_ok, count));
            BackupManager.get(this).backupNow(new BackupManager.Callback() {
                @Override
                public void onResult(BackupRecord record) {
                    showBackupState();
                }
            });
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.import_db_failed, String.valueOf(e.getMessage())));
            db.reload();
        } finally {
            pendingDb = null;
            showDbState();
        }
    }

    private static void deleteSidecar(File dbFile, String suffix) {
        File sidecar = new File(dbFile.getAbsolutePath() + suffix);
        if (sidecar.exists()) {
            //noinspection ResultOfMethodCallIgnored
            sidecar.delete();
        }
    }

    // ---------- CSV ----------

    private void importCsv(Uri uri, String name) throws Exception {
        File temp = Storage.readToCache(this, uri, "csv-" + name);
        StringBuilder text = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(temp), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        } finally {
            reader.close();
        }
        String content = text.toString();
        if (content.length() > 0 && content.charAt(0) == '﻿') {
            content = content.substring(1);   // 去掉 Excel 用的 BOM
        }
        int rows = db.records().importRows(Csv.parse(content), db.types());
        db.changed();
        Ui.toast(this, getString(R.string.csv_imported, rows));
        showDbState();
    }

    /** 导出先落到缓存，再让系统保存框决定文件名和位置 */
    private void exportCsv() {
        if (!db.crypto().unlocked()) {
            Ui.toast(this, getString(R.string.err_locked_write));
            return;
        }
        withWritePermission(new Runnable() {
            @Override
            public void run() {
                try {
                    String content = Csv.export(db.records().exportable());
                    File temp = new File(getCacheDir(), "pymoney-out.csv");
                    FileOutputStream out = new FileOutputStream(temp);
                    try {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    } finally {
                        out.close();
                    }
                    pendingCsv = temp;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/csv");
                    intent.putExtra(Intent.EXTRA_TITLE, "pymoney_"
                            + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(new Date()) + ".csv");
                    startActivityForResult(intent, REQ_CSV_OUT);
                } catch (Exception e) {
                    Ui.toast(ImportExportActivity.this,
                            getString(R.string.backup_failed, String.valueOf(e.getMessage())));
                }
            }
        });
    }

    private void finishCsvExport(Uri uri) {
        if (pendingCsv == null || !pendingCsv.exists()) {
            Ui.toast(this, getString(R.string.backup_failed, "临时文件已经不在了，请再点一次"));
            return;
        }
        try {
            Storage.Saved saved = Storage.overwrite(this, uri, pendingCsv);
            Ui.toast(this, getString(R.string.csv_exported,
                    (int) db.records().count(), saved.path));
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.backup_failed, String.valueOf(e.getMessage())));
        } finally {
            pendingCsv = null;
        }
    }

    // ---------- 老系统的存储权限 ----------

    /** Android 9 及以下写公共目录要先拿权限 */
    private void withWritePermission(Runnable action) {
        if (!Storage.needsWritePermission()) {
            action.run();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        afterPermission = action;
        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERM && results.length > 0
                && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                && afterPermission != null) {
            afterPermission.run();
        }
        afterPermission = null;
    }
}
