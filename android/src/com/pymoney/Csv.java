package com.pymoney;

import com.pymoney.calc.MoneyFormat;
import com.pymoney.model.Entry;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 读写，格式与早期的 moneyold.csv 一致：Time,Name,Amount,TypeName。
 * 金额单位是元，支出为负数；导出时带 UTF-8 BOM，Excel 打开不乱码。
 */
public final class Csv {

    public static final String HEADER = "Time,Name,Amount,TypeName";

    private Csv() {
    }

    public static String export(List<Entry> rows) {
        StringBuilder text = new StringBuilder();
        text.append('\uFEFF').append(HEADER).append('\n');
        for (Entry row : rows) {
            text.append(row.time).append(',')
                    .append(quote(row.name)).append(',')
                    .append(MoneyFormat.fenToYuan(row.amount).toPlainString()).append(',')
                    .append(quote(row.typeName == null ? "" : row.typeName)).append('\n');
        }
        return text.toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * 解析 CSV，返回 [Time(Integer), Name(String), Amount(Long 分), TypeName(String)]。
     * 表头、空行、字段不足 4 个的行会被跳过。
     */
    public static List<Object[]> parse(String text) {
        List<Object[]> rows = new ArrayList<>();
        for (List<String> fields : parseLines(text)) {
            if (fields.size() < 4) {
                continue;
            }
            String time = fields.get(0).trim();
            if (time.isEmpty() || !isDigits(time) || time.length() != 8) {
                continue;   // 表头或坏行
            }
            String name = fields.get(1);
            String amount = fields.get(2).trim();
            String typeName = fields.get(3).trim();
            long fen;
            try {
                fen = new BigDecimal(amount).movePointRight(2)
                        .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
            } catch (RuntimeException e) {
                continue;
            }
            rows.add(new Object[]{Integer.valueOf(time), name, Long.valueOf(fen), typeName});
        }
        return rows;
    }

    /** 逐字符解析，支持带引号字段里的逗号和换行 */
    private static List<List<String>> parseLines(String text) {
        List<List<String>> lines = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"':
                    quoted = true;
                    break;
                case ',':
                    current.add(field.toString());
                    field.setLength(0);
                    break;
                case '\r':
                    break;
                case '\n':
                    current.add(field.toString());
                    field.setLength(0);
                    lines.add(current);
                    current = new ArrayList<>();
                    break;
                default:
                    field.append(c);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            lines.add(current);
        }
        return lines;
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
