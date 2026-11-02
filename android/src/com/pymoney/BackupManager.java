package com.pymoney;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.pymoney.core.BackupLog;
import com.pymoney.core.BackupRecord;
import com.pymoney.core.Codec;
import com.pymoney.core.CryptoState;
import com.pymoney.db.AndroidSql;
import com.pymoney.db.AppDb;
import com.pymoney.db.Fingerprint;
import com.pymoney.db.Migrator;
import com.pymoney.db.Schema;
import com.pymoney.widget.QuickAddWidget;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

/**
 * 备份：把数据库拷到用户选的位置，并且证明它真的落下去了。
 * 「证明」不是看有没有抛异常，而是写完把文件读回来、打开、算指纹，和快照逐字段比。
 * 老代码只做到「调用没报错就说成功」，路径还是字符串拼的，所以你 cp 走的那份可能一直是旧的。
 *
 * 线程：快照、写文件、回读全在 worker 上做，主线程只负责回调。
 */
public final class BackupManager {

    private static final long DEBOUNCE_MS = 2000L;
    private static final int BUSY_RETRY = 5;
    private static final long BUSY_WAIT_MS = 1000L;

    private static BackupManager instance;

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler worker;
    private final Prefs prefs;
    private Runnable pending;

    private BackupManager(Context context) {
        appContext = context.getApplicationContext();
        prefs = new Prefs(appContext);
        // HandlerThread 没 start() 之前 getLooper() 返回 null，而 new Handler(null) 就是在
        // looper.mQueue 上抛 NullPointerException。真机上「点立即备份/记完一笔就闪回主界面、
        // 屏幕上只留下一句 mQueue 的 NPE」全部来自这一行：必须先 start() 再取 Looper。
        android.os.HandlerThread thread = new android.os.HandlerThread("backup");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public static synchronized BackupManager get(Context context) {
        if (instance == null) {
            instance = new BackupManager(context);
        }
        return instance;
    }

    public interface Callback {
        void onResult(BackupRecord record);
    }

    /** 数据改动后调用，2 秒内多次改动只备份一次；「每次问我」这种没法自动跑的模式直接跳过 */
    public void scheduleBackup() {
        QuickAddWidget.refresh(appContext);
        if (!prefs.autoBackup()) {
            return;
        }
        if (!BackupTarget.create(prefs).unattended()) {
            return;
        }
        if (pending != null) {
            main.removeCallbacks(pending);
        }
        pending = new Runnable() {
            @Override
            public void run() {
                start(BackupTarget.create(prefs), false, null);
            }
        };
        main.postDelayed(pending, DEBOUNCE_MS);
    }

    /** 立即备份到当前设置的位置 */
    public void backupNow(Callback callback) {
        start(BackupTarget.create(prefs), false, callback);
    }

    /** 「每次问我」：用户在系统保存框里点完那个 uri 之后走这里 */
    public void backupToUri(Uri uri, Callback callback) {
        start(new OneShot(uri), false, callback);
    }

    /**
     * 同上，但那份必须是明文：用户点的是「导出明文」，备份那条路失败后转去「另存为」，
     * 这时候写出去的还是密文就等于骗了他。文件名由那个保存框里他填的算。
     */
    public void backupPlainToUri(Uri uri, Callback callback) {
        start(new OneShot(uri), true, callback);
    }

    /** 明文导出专用的文件名 */
    public static final String PLAIN_NAME = "money-plain.db";

    /**
     * 「删之前先导出」：把私有目录里那份明文副本原样送去 Download/PyMoney/money-plain.db。
     * 它本来就是明文，不用解锁、也不用再解一遍；删掉之前先落一份到能被 Termux 读到的地方。
     */
    public void exportCopy(File source, Callback callback) {
        start(source, new BackupTarget.Media(), true, callback);
    }

    private static final class OneShot extends BackupTarget {

        private final Uri uri;

        OneShot(Uri uri) {
            this.uri = uri;
        }

        @Override
        public boolean available(Context context) {
            return uri != null;
        }

        @Override
        public boolean unattended() {
            return false;
        }

        @Override
        public String label() {
            return LABEL_SINGLE;
        }

        @Override
        public String describe(Context context) {
            return Storage.describeUri(context, uri);
        }

        @Override
        public Storage.Saved write(Context context, String name, String mime, File source)
                throws IOException {
            return Storage.overwrite(context, uri, source);
        }
    }

    private void start(final BackupTarget target, final boolean plaintext,
                       final Callback callback) {
        start(null, target, plaintext, callback);
    }

    /**
     * @param explicitSource 已经躺在私有目录里的那份文件（「删之前先导出」用）。
     *                       给了它就跳过快照——别人的文件不能当临时文件删掉。
     */
    private void start(final File explicitSource, final BackupTarget target,
                       final boolean plaintext, final Callback callback) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                BackupRecord record = new BackupRecord();
                // 迁移副本满 7 天就该没了，借这个本来就在后台的时机清一次
                PlainCopy.sweepExpired(appContext);
                record.at = System.currentTimeMillis();
                record.target = plaintext ? target.label() + "+plain" : target.label();
                File snapshot = null;
                File readBack = null;
                try {
                    File source = explicitSource;
                    if (source == null) {
                        snapshot = snapshotWithRetry();
                        source = snapshot;
                    } else if (!source.exists()) {
                        throw new IOException(source.getName() + " 已经不在了");
                    }
                    if (plaintext && snapshot != null) {
                        unseal(source);
                    }
                    Fingerprint expected = fingerprintOf(source);
                    Storage.Saved saved = target.write(appContext,
                            plaintext ? PLAIN_NAME : AppDb.DB_NAME,
                            "application/octet-stream", source);
                    record.path = saved.path;
                    if (saved.bytes > 0) {
                        record.bytes = saved.bytes;
                    }

                    readBack = Storage.readBackAfterWrite(appContext, saved, AppDb.DB_NAME);
                    Fingerprint actual = fingerprintOf(readBack);
                    if (!expected.sameContent(actual)) {
                        throw new IOException("回读内容和快照对不上：" + expected.diff(actual));
                    }
                    record.count = actual.count;
                    record.maxId = actual.maxId;
                    record.minTime = actual.minTime;
                    record.maxTime = actual.maxTime;
                    record.sumAmount = actual.sumAmount;
                    record.bytes = readBack.length();
                    record.sha8 = sha8(readBack);
                    record.ok = true;
                    prefs.setLastBackupAt(record.at);
                    prefs.setLastBackupError(null);
                } catch (Throwable t) {
                    record.ok = false;
                    record.error = describe(t);
                    record.path = record.path == null ? "" : record.path;
                    prefs.setLastBackupError(record.error);
                } finally {
                    deleteQuietly(snapshot);
                    deleteQuietly(readBack);
                    prefs.setBackupHistory(BackupLog.append(prefs.backupHistory(), record));
                }
                if (callback != null) {
                    final BackupRecord result = record;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(result);
                        }
                    });
                }
            }
        });
    }

    /** 事务进行中 VACUUM 会失败，稍等一下再来，最多五次 */
    private File snapshotWithRetry() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < BUSY_RETRY; attempt++) {
            try {
                return snapshot();
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(BUSY_WAIT_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last == null ? new RuntimeException("拿不到数据库快照") : last;
    }

    /** 用 VACUUM INTO 取一致性快照；老系统不支持时退回直接拷贝 */
    private File snapshot() {
        AppDb db = AppDb.get(appContext);
        File target = new File(appContext.getCacheDir(), "snapshot-" + AppDb.DB_NAME);
        if (target.exists() && !target.delete()) {
            target = new File(appContext.getCacheDir(),
                    "snapshot-" + System.currentTimeMillis() + "-" + AppDb.DB_NAME);
        }
        try {
            db.sql().execute("VACUUM INTO ?", target.getAbsolutePath());
            return target;
        } catch (Exception e) {
            File fallback = new File(appContext.getCacheDir(),
                    "copy-" + System.currentTimeMillis() + "-" + AppDb.DB_NAME);
            try {
                Storage.copy(db.file(), fallback);
                return fallback;
            } catch (IOException io) {
                throw new RuntimeException("生成快照失败：" + io.getMessage());
            }
        }
    }

    /**
     * 明文导出：账本加密时，把快照就地解回明文再写出去；没加密就是普通备份，直接透过去。
     * 解不开（口令没输）当成失败，绝不写一份带掩码的「假明文」出去。
     */
    private void unseal(File file) throws IOException {
        CryptoState state = AppDb.get(appContext).crypto();
        if (!state.encrypted()) {
            return;
        }
        if (!state.unlocked()) {
            throw new IOException("账本已加密，要先输入口令才能导出明文");
        }
        Codec codec = state.codec();
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            Migrator.decryptCopy(new AndroidSql(database), codec);
            database.close();
            database = null;
        } catch (Exception e) {
            throw new IOException("快照没能解成明文：" + e.getMessage());
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Exception ignored) {
                    // 关不掉就算了，这个临时文件马上就要删
                }
            }
        }
    }

    /** 打开一份库文件算指纹，只读，不会碰 WAL、也不会改动它 */
    private static Fingerprint fingerprintOf(File file) throws IOException {
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            AndroidSql sql = new AndroidSql(database);
            if (!Schema.looksLikeMoneyDb(sql)) {
                throw new IOException(file.getName() + " 里找不到 Income/IncomeType，不是记账库");
            }
            return Fingerprint.of(sql).withFileSize(file.length());
        } catch (IOException io) {
            throw io;
        } catch (Exception e) {
            throw new IOException("打不开 " + file.getName() + "：" + e.getMessage());
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Exception ignored) {
                    // 关掉失败不用管
                }
            }
        }
    }

    private static String sha8(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            } finally {
                in.close();
            }
            byte[] hash = digest.digest();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 4 && i < hash.length; i++) {
                text.append(String.format("%02x", hash[i] & 0xFF));
            }
            return text.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

}
