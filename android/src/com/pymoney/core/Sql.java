package com.pymoney.core;

import java.util.List;

/**
 * 数据库抽象层。真机用 Android 的 SQLiteDatabase 实现，
 * 对拍测试用 JDBC 实现，这样计算逻辑能脱离安卓在 JVM 上验证。
 */
public interface Sql {

    /** 查询，返回若干行；每行是一个对象数组，列名顺序与 SQL 一致 */
    List<Object[]> select(String sql, Object... args);

    /** 执行不返回结果的语句（建表、删除、更新） */
    void execute(String sql, Object... args);

    /** 插入，返回自增 rowId */
    long insert(String sql, Object... args);

    /** 在事务里执行一段逻辑，异常则回滚 */
    void transaction(Runnable body);

    void close();
}
