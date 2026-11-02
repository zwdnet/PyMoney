package com.pymoney.harness;

import com.pymoney.core.Sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

/** 对拍测试用的 Sql 实现：桌面 JVM + sqlite-jdbc，让核心计算层能脱离安卓跑起来。 */
public class JdbcSql implements Sql {

    private final Connection con;

    public JdbcSql(String dbPath) {
        try {
            Class.forName("org.sqlite.JDBC");
            con = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (Exception e) {
            throw new RuntimeException("打开数据库失败: " + dbPath, e);
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                ps.setNull(i + 1, java.sql.Types.NULL);
            } else if (arg instanceof Number) {
                ps.setLong(i + 1, ((Number) arg).longValue());
            } else {
                ps.setString(i + 1, arg.toString());
            }
        }
    }

    @Override
    public List<Object[]> select(String sql, Object... args) {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                List<Object[]> rows = new ArrayList<>();
                while (rs.next()) {
                    Object[] row = new Object[columns];
                    for (int i = 0; i < columns; i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (Exception e) {
            throw new RuntimeException("查询失败: " + sql, e);
        }
    }

    @Override
    public void execute(String sql, Object... args) {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            ps.execute();
        } catch (Exception e) {
            throw new RuntimeException("执行失败: " + sql, e);
        }
    }

    @Override
    public long insert(String sql, Object... args) {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            ps.execute();
        } catch (Exception e) {
            throw new RuntimeException("插入失败: " + sql, e);
        }
        try (PreparedStatement ps = con.prepareStatement("SELECT last_insert_rowid()");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception e) {
            throw new RuntimeException("取 rowid 失败", e);
        }
    }

    @Override
    public void transaction(Runnable body) {
        try {
            boolean previous = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                body.run();
                con.commit();
            } catch (RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(previous);
            }
        } catch (Exception e) {
            throw new RuntimeException("事务失败", e);
        }
    }

    @Override
    public void close() {
        try {
            con.close();
        } catch (Exception ignored) {
            // 关掉就行，不必声张
        }
    }
}
