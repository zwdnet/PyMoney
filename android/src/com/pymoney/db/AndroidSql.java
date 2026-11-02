package com.pymoney.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.pymoney.core.Sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Sql 接口的安卓实现。
 * 写入用 execSQL(sql, Object[])，Long 会按整数类型绑定；
 * 查询参数只能是字符串，SQLite 会按列的 affinity 把数字字符串转成整数再比较，
 * Time/Amount 这些列都是整数 affinity，所以结果和原程序一致。
 */
public class AndroidSql implements Sql {

    private final SQLiteDatabase db;
    /** 备份线程要靠它避让：事务中间 VACUUM 会直接报错 */
    private volatile boolean inTransaction;

    public AndroidSql(SQLiteDatabase db) {
        this.db = db;
    }

    public boolean isInTransaction() {
        return inTransaction;
    }

    public SQLiteDatabase getDatabase() {
        return db;
    }

    private static String[] toStringArgs(Object... args) {
        String[] texts = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            texts[i] = args[i] == null ? null : String.valueOf(args[i]);
        }
        return texts;
    }

    @Override
    public List<Object[]> select(String sql, Object... args) {
        List<Object[]> rows = new ArrayList<>();
        Cursor cursor = db.rawQuery(sql, toStringArgs(args));
        try {
            int columns = cursor.getColumnCount();
            while (cursor.moveToNext()) {
                Object[] row = new Object[columns];
                for (int i = 0; i < columns; i++) {
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER:
                            row[i] = cursor.getLong(i);
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            row[i] = cursor.getDouble(i);
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            row[i] = cursor.getString(i);
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            row[i] = cursor.getBlob(i);
                            break;
                        default:
                            row[i] = null;
                    }
                }
                rows.add(row);
            }
        } finally {
            cursor.close();
        }
        return rows;
    }

    @Override
    public void execute(String sql, Object... args) {
        if (args.length == 0) {
            db.execSQL(sql);
        } else {
            Object[] bind = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Integer) {
                    bind[i] = ((Integer) arg).longValue();
                } else {
                    bind[i] = arg;
                }
            }
            db.execSQL(sql, bind);
        }
    }

    @Override
    public long insert(String sql, Object... args) {
        execute(sql, args);
        Cursor cursor = db.rawQuery("SELECT last_insert_rowid()", null);
        try {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } finally {
            cursor.close();
        }
    }

    @Override
    public void transaction(Runnable body) {
        db.beginTransaction();
        inTransaction = true;
        try {
            body.run();
            db.setTransactionSuccessful();
        } finally {
            inTransaction = false;
            db.endTransaction();
        }
    }

    @Override
    public void close() {
        // 数据库生命周期由 AppDb 统一管，这里不关
    }
}
