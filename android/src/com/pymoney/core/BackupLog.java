package com.pymoney.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 最近几次备份的历史，整串存在 SharedPreferences 的一个 String 里。
 * 最新的排前面，超过上限的丢掉。纯 Java，方便在 JVM 上直接验证序列化和上限。
 */
public final class BackupLog {

    public static final int LIMIT = 8;
    public static final String ROWS = "\n";

    private BackupLog() {
    }

    /** 追加一条，返回新的整串 */
    public static String append(String existing, BackupRecord record) {
        if (record == null) {
            return existing == null ? "" : existing;
        }
        List<BackupRecord> rows = parse(existing);
        rows.add(0, record);
        while (rows.size() > LIMIT) {
            rows.remove(rows.size() - 1);
        }
        StringBuilder text = new StringBuilder();
        for (BackupRecord row : rows) {
            if (text.length() > 0) {
                text.append(ROWS);
            }
            text.append(row.encode());
        }
        return text.toString();
    }

    public static List<BackupRecord> parse(String stored) {
        List<BackupRecord> rows = new ArrayList<>();
        if (stored == null || stored.length() == 0) {
            return rows;
        }
        int start = 0;
        while (start <= stored.length()) {
            int index = stored.indexOf(ROWS, start);
            String line = index < 0 ? stored.substring(start) : stored.substring(start, index);
            BackupRecord record = BackupRecord.decode(line);
            if (record != null) {
                rows.add(record);
            }
            if (index < 0) {
                break;
            }
            start = index + ROWS.length();
        }
        return rows;
    }

    public static BackupRecord latest(String stored) {
        List<BackupRecord> rows = parse(stored);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 最近一次「校验通过」的备份时间，没有就返回 0。开启加密前要确认它存在 */
    public static long lastVerifiedAt(String stored) {
        for (BackupRecord record : parse(stored)) {
            if (record.ok) {
                return record.at;
            }
        }
        return 0L;
    }

    /** 成功过且指纹等于给定 compact 串的记录数，用来回答「这份数据至少安全落过一次了吗」 */
    public static boolean hasVerified(String stored) {
        return lastVerifiedAt(stored) > 0L;
    }
}
