package com.pymoney.db;

import com.pymoney.calc.MoneyFormat;
import com.pymoney.core.Sql;

import java.util.List;
import java.util.Locale;

/**
 * 库内容指纹。备份写完回读一份、算出指纹和当前库逐字段比对，
 * 才能把「已备份」从一句拼出来的提示变成可核对的事实。
 * 迁移加密前后也用它证明「一条没丢、一分没变」。
 */
public final class Fingerprint {

    public long count;
    public long maxId;
    public int minTime;
    public int maxTime;
    /** 所有 Amount 相加（分），正负相抵后的净值，对内容变化最敏感 */
    public long sumAmount;
    public long typeCount;
    /** 只有配了文件才算，不参与 sameContent */
    public long fileSize;

    private Fingerprint() {
    }

    public static Fingerprint of(Sql sql) {
        Fingerprint mark = new Fingerprint();
        List<Object[]> rows = sql.select("SELECT COUNT(*), MAX(ID), MIN(Time), MAX(Time),"
                + " COALESCE(SUM(Amount), 0) FROM Income");
        if (!rows.isEmpty()) {
            Object[] row = rows.get(0);
            mark.count = RecordDao.asLong(row[0]);
            mark.maxId = RecordDao.asLong(row[1]);
            mark.minTime = (int) RecordDao.asLong(row[2]);
            mark.maxTime = (int) RecordDao.asLong(row[3]);
            mark.sumAmount = RecordDao.asLong(row[4]);
        }
        List<Object[]> types = sql.select("SELECT COUNT(*) FROM IncomeType");
        if (!types.isEmpty()) {
            mark.typeCount = RecordDao.asLong(types.get(0)[0]);
        }
        return mark;
    }

    public Fingerprint withFileSize(long bytes) {
        fileSize = bytes;
        return this;
    }

    /** 只看内容，文件大小不算（同一份数据 VACUUM 前后大小不同） */
    public boolean sameContent(Fingerprint other) {
        if (other == null) {
            return false;
        }
        return count == other.count && maxId == other.maxId && minTime == other.minTime
                && maxTime == other.maxTime && sumAmount == other.sumAmount
                && typeCount == other.typeCount;
    }

    /** 差异说明，用于「回读内容对不上」时告诉你到底哪里对不上 */
    public String diff(Fingerprint other) {
        if (other == null) {
            return "读不出指纹";
        }
        StringBuilder text = new StringBuilder();
        appendDiff(text, "条数", count, other.count);
        appendDiff(text, "maxID", maxId, other.maxId);
        appendDiff(text, "最早", minTime, other.minTime);
        appendDiff(text, "最晚", maxTime, other.maxTime);
        appendDiff(text, "金额合计", sumAmount, other.sumAmount);
        appendDiff(text, "类别数", typeCount, other.typeCount);
        return text.length() == 0 ? "完全一致" : text.toString();
    }

    private static void appendDiff(StringBuilder text, String label, long mine, long theirs) {
        if (mine == theirs) {
            return;
        }
        if (text.length() > 0) {
            text.append("；");
        }
        text.append(label).append(' ').append(mine).append(" ≠ ").append(theirs);
    }

    /** 给用户看的一行：12877 条 / maxID 12924 / 20111018~20261102 / 净值 -1,234.56 元 */
    public String describe() {
        return String.format(Locale.US, "%d 条 / maxID %d / %d~%d / 净值 %s 元 / %d 类",
                count, maxId, minTime, maxTime,
                MoneyFormat.fenToYuanText(sumAmount), typeCount);
    }

    /** 存进备份历史用的紧凑一行（不含空格，方便和别的字段拼） */
    public String compact() {
        return String.format(Locale.US, "%d,%d,%d,%d,%d,%d",
                count, maxId, minTime, maxTime, sumAmount, typeCount);
    }
}
