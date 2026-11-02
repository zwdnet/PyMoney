package com.pymoney.core;

import java.util.Locale;

/**
 * 一次备份的结果记录。纯 Java、自己定行的分隔符，
 * 这样它能待在 SharedPreferences 里，也能在 JVM 对拍测试里被直接验证。
 */
public final class BackupRecord {

    public static final String SEPARATOR = "|";
    /** 12 个字段，多一个都要同步改 encode/decode */
    public static final int FIELD_COUNT = 12;

    public long at;
    public boolean ok;
    /** media / tree / single */
    public String target = "";
    /** 写完之后回读定位到的真实可寻址路径 */
    public String path = "";
    public long bytes;
    public long count;
    public long maxId;
    public int minTime;
    public int maxTime;
    /** 分 */
    public long sumAmount;
    public String sha8 = "";
    public String error = "";

    public BackupRecord() {
    }

    public String encode() {
        StringBuilder text = new StringBuilder();
        append(text, String.valueOf(at));
        append(text, ok ? "1" : "0");
        append(text, target);
        append(text, path);
        append(text, String.valueOf(bytes));
        append(text, String.valueOf(count));
        append(text, String.valueOf(maxId));
        append(text, String.valueOf(minTime));
        append(text, String.valueOf(maxTime));
        append(text, String.valueOf(sumAmount));
        append(text, sha8);
        append(text, error);
        return text.toString();
    }

    /** 解不出来返回 null（历史串被人为改坏时不要把异常抛到界面上） */
    public static BackupRecord decode(String line) {
        if (line == null || line.length() == 0) {
            return null;
        }
        String[] parts = split(line);
        if (parts.length < FIELD_COUNT) {
            return null;
        }
        BackupRecord record = new BackupRecord();
        try {
            record.at = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        record.ok = "1".equals(parts[1]);
        record.target = parts[2];
        record.path = parts[3];
        record.bytes = parseLong(parts[4], 0L);
        record.count = parseLong(parts[5], 0L);
        record.maxId = parseLong(parts[6], 0L);
        record.minTime = (int) parseLong(parts[7], 0L);
        record.maxTime = (int) parseLong(parts[8], 0L);
        record.sumAmount = parseLong(parts[9], 0L);
        record.sha8 = parts[10];
        record.error = parts[11];
        return record;
    }

    /** 一行给人看的摘要 */
    public String summary() {
        if (!ok) {
            return "❌ " + (error.length() == 0 ? "失败" : error);
        }
        return String.format(Locale.US, "✅ %d 条 / maxID %d / 净值 %.2f 元 / %.0f KB / %s",
                count, maxId, sumAmount / 100.0, bytes / 1024.0,
                sha8.length() == 0 ? "" : sha8);
    }

    /** 明细第二行：路径 */
    public String location() {
        return "[" + targetModeLabel() + "] " + path;
    }

    public String targetModeLabel() {
        // 明文导出走的是同一个位置，只是内容不加密，历史上用 "media+plain" 记这一笔
        boolean plain = target.endsWith("+plain");
        String mode = plain ? target.substring(0, target.length() - 6) : target;
        String label;
        if ("tree".equals(mode)) {
            label = "自选文件夹";
        } else if ("single".equals(mode)) {
            label = "每次问我";
        } else if ("media".equals(mode)) {
            label = "Download/PyMoney";
        } else {
            return mode;
        }
        return plain ? label + " · 明文" : label;
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void append(StringBuilder text, String value) {
        if (text.length() > 0) {
            text.append(SEPARATOR);
        }
        text.append(sanitize(value));
    }

    /** 分隔符和换行都不许出现在字段里，否则历史串就解不开了 */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(SEPARATOR, "¦").replace('\n', ' ').replace('\r', ' ');
    }

    private static String[] split(String line) {
        // 手工切，不用 String.split 的正则（省点开销，也好在 JVM 上一致）
        java.util.List<String> parts = new java.util.ArrayList<String>();
        int start = 0;
        while (true) {
            int index = line.indexOf(SEPARATOR, start);
            if (index < 0) {
                parts.add(line.substring(start));
                break;
            }
            parts.add(line.substring(start, index));
            start = index + SEPARATOR.length();
        }
        return parts.toArray(new String[parts.size()]);
    }
}
