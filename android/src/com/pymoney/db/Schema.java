package com.pymoney.db;

import com.pymoney.core.Sql;

/**
 * 表结构与原程序 InitDatabase.py 完全一致，这样手机上能直接导入十几年攒下的 money.db。
 * 列类型声明也不要改：Amount 声明成 DECIMAL(7,2) 实际是 NUMERIC affinity，
 * 原程序往里写的是整数「分」，我们同样只写 Long。
 */
public final class Schema {

    public static final String CREATE_INCOME =
            "CREATE TABLE IF NOT EXISTS Income("
                    + "ID INTEGER PRIMARY KEY, Time DATE, Name NVARCHAR, Amount DECIMAL(7,2), TypeID INTEGER)";

    public static final String CREATE_TYPE =
            "CREATE TABLE IF NOT EXISTS IncomeType("
                    + "TypeID INTEGER PRIMARY KEY, TypeName NVARCHAR)";

    /**
     * 加密信封。只加新表，Income/IncomeType 一个列都不动，
     * 原程序（Termux 那边）只读那两张表，多一张表对它完全无感。
     */
    public static final String CREATE_CRYPTO =
            "CREATE TABLE IF NOT EXISTS AppCrypto(k TEXT PRIMARY KEY, v TEXT)";

    /** 索引是新增的，不影响与原程序互认数据；老库里没有索引也能正常读写 */
    private static final String[] INDEXES = {
            "CREATE INDEX IF NOT EXISTS ix_income_time ON Income(Time)",
            "CREATE INDEX IF NOT EXISTS ix_income_type ON Income(TypeID)",
    };

    private Schema() {
    }

    public static void ensure(Sql sql) {
        sql.execute(CREATE_INCOME);
        sql.execute(CREATE_TYPE);
        sql.execute(CREATE_CRYPTO);
        for (String index : INDEXES) {
            sql.execute(index);
        }
    }

    /** 判断一个数据库文件是否长得像 money.db */
    public static boolean looksLikeMoneyDb(Sql sql) {
        return hasTable(sql, "Income") && hasTable(sql, "IncomeType");
    }

    private static boolean hasTable(Sql sql, String table) {
        return !sql.select("SELECT name FROM sqlite_master WHERE type='table' AND name=?", table).isEmpty();
    }
}
