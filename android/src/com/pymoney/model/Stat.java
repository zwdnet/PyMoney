package com.pymoney.model;

/** 一个聚合桶：key 可能是年份(2024)、年月(202403)或类型ID，name 用于显示。金额单位是分。 */
public class Stat {
    public int key;
    public String name;
    public long income;
    public long expense;   // 负数
    public long count;
    public long total;     // income + expense

    public Stat() {
    }

    public Stat(int key, String name, long income, long expense) {
        this.key = key;
        this.name = name;
        this.income = income;
        this.expense = expense;
        this.total = income + expense;
    }

    public long balance() {
        return income + expense;
    }

    /** 储蓄率，没有收入时返回 NaN */
    public double savingRate() {
        if (income <= 0) {
            return Double.NaN;
        }
        return balance() * 100.0 / income;
    }
}
