package com.pymoney.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.pymoney.core.CryptoState;
import com.pymoney.core.Sql;

import java.io.File;

/**
 * 全局数据库持有者。库文件就是原程序那套表结构，位置在 App 私有目录：
 * /data/data/com.pymoney/databases/money.db
 */
public final class AppDb {

    public static final String DB_NAME = "money.db";

    private static AppDb instance;

    private final Context appContext;
    private SQLiteDatabase database;
    private Sql sql;
    private RecordDao records;
    private TypeDao types;
    private AggQuery agg;
    private CryptoState crypto;

    private AppDb(Context context) {
        appContext = context.getApplicationContext();
        open();
    }

    public static synchronized AppDb get(Context context) {
        if (instance == null) {
            instance = new AppDb(context);
        }
        return instance;
    }

    private void open() {
        File file = file();
        database = SQLiteDatabase.openOrCreateDatabase(file, null);
        sql = new AndroidSql(database);
        // 两步走：先把表建齐（含 AppCrypto），再认这个库到底加密了没有
        Schema.ensure(sql);
        crypto = CryptoState.resolve(sql);
        records = new RecordDao(sql, crypto);
        types = new TypeDao(sql, crypto);
        agg = new AggQuery(sql, crypto);
    }

    /**
     * 导入外部数据库后需要重新打开。
     * 注意这会丢掉解锁状态：换了库就得重新输入口令，不能拿上一把钥去解这一本。
     */
    public synchronized void reload() {
        if (crypto != null) {
            crypto.lock();
        }
        close();
        open();
    }

    public synchronized void close() {
        try {
            if (database != null && database.isOpen()) {
                database.close();
            }
        } catch (Exception ignored) {
            // 已经关了就不用再吵
        }
        database = null;
    }

    public File file() {
        File dir = appContext.getDatabasePath(DB_NAME).getParentFile();
        if (dir != null && !dir.exists()) {
            // 首次安装时 databases 目录还不存在，openOrCreateDatabase 不会替你建
            dir.mkdirs();
        }
        return new File(dir, DB_NAME);
    }

    public Sql sql() {
        return sql;
    }

    /** 「这库加密了吗、现在解开了吗」的唯一权威，DAO 每次读写都现从这里取编解码器 */
    public CryptoState crypto() {
        return crypto;
    }

    public RecordDao records() {
        return records;
    }

    public TypeDao types() {
        return types;
    }

    public AggQuery agg() {
        return agg;
    }

    public Context context() {
        return appContext;
    }

    /** 数据有变动：触发自动备份和桌面小组件刷新 */
    public void changed() {
        com.pymoney.BackupManager.get(appContext).scheduleBackup();
        com.pymoney.widget.QuickAddWidget.refresh(appContext);
    }
}
