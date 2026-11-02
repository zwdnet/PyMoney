package com.pymoney.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;

/** 金额与日期的换算。金额在库里永远是整数「分」，避免浮点误差（沿用原 tools.py 的思路）。 */
public final class MoneyFormat {

    private MoneyFormat() {
    }

    /** 元字符串 → 分。用 BigDecimal 精确乘 100，四舍五入。 */
    public static long yuanToFen(String text) {
        BigDecimal value = new BigDecimal(text.trim());
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 分 → 保留两位小数的元 */
    public static BigDecimal fenToYuan(long fen) {
        return new BigDecimal(fen).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    /** 分 → 带千分位的元字符串，如 -1,234.50 */
    public static String fenToYuanText(long fen) {
        BigDecimal yuan = fenToYuan(fen);
        boolean negative = yuan.signum() < 0;
        String plain = yuan.abs().toPlainString();
        int dot = plain.indexOf('.');
        String intPart = plain.substring(0, dot);
        String fraction = plain.substring(dot);
        StringBuilder grouped = new StringBuilder();
        int seen = 0;
        for (int i = intPart.length() - 1; i >= 0; i--) {
            grouped.append(intPart.charAt(i));
            seen++;
            if (seen % 3 == 0 && i > 0) {
                grouped.append(',');
            }
        }
        String body = grouped.reverse() + fraction;
        return (negative ? "-" : "") + body;
    }

    /** 分 → 元的 double，仅供图表绘制使用 */
    public static double fenToYuanDouble(long fen) {
        return fen / 100.0;
    }

    /** 日历字段 → YYYYMMDD 整数 */
    public static int toDateInt(int year, int month, int day) {
        return year * 10000 + month * 100 + day;
    }

    /** 今天的 YYYYMMDD */
    public static int today() {
        Calendar c = Calendar.getInstance();
        return toDateInt(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 某月第一天，如 202403 → 20240301 */
    public static int firstDayOfMonth(int yearMonth) {
        return yearMonth * 100 + 1;
    }

    /** 某月最后一天，含闰年判断 */
    public static int lastDayOfMonth(int yearMonth) {
        int year = yearMonth / 100;
        int month = yearMonth % 100;
        return yearMonth * 100 + daysInMonth(year, month);
    }

    public static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || (year % 100 == 0 && year % 400 == 0);
    }

    public static int daysInMonth(int year, int month) {
        switch (month) {
            case 1: case 3: case 5: case 7: case 8: case 10: case 12:
                return 31;
            case 4: case 6: case 9: case 11:
                return 30;
            case 2:
                return isLeapYear(year) ? 29 : 28;
            default:
                return 0;
        }
    }

    /** 校验 YYYYMMDD，逻辑照搬 tools.py 的 JudgeDate：范围 19800101~21000101，含闰年 */
    public static boolean isValidDate(int time) {
        if (time < 19800101 || time > 21000101) {
            return false;
        }
        int year = time / 10000;
        int rest = time % 10000;
        int month = rest / 100;
        int day = rest % 100;
        if (month < 1 || month > 12) {
            return false;
        }
        if (day < 1 || day > daysInMonth(year, month)) {
            return false;
        }
        return true;
    }

    /** 20240315 → 2024-03-15 */
    public static String formatDate(int time) {
        return String.format("%04d-%02d-%02d", time / 10000, (time / 100) % 100, time % 100);
    }

    /** 20240315 → 24-03-15，列表里省点地方 */
    public static String formatDateShort(int time) {
        return String.format("%02d-%02d-%02d", (time / 10000) % 100, (time / 100) % 100, time % 100);
    }

    /** 202403 → 2024-03 */
    public static String formatYearMonth(int yearMonth) {
        return String.format("%04d-%02d", yearMonth / 100, yearMonth % 100);
    }

    public static int yearOf(int time) {
        return time / 10000;
    }

    public static int yearMonthOf(int time) {
        return time / 100;
    }
}
