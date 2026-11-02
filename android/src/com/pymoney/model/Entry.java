package com.pymoney.model;

/** 一条收入支出记录，对应表 Income。amount 单位为「分」，负数是支出。time 是 YYYYMMDD 整数。 */
public class Entry {
    public long id;
    public int time;
    public String name;
    public long amount;
    public int typeId;
    public String typeName;   // 关联查询时填充，可能为 null

    public Entry() {
    }

    public Entry(long id, int time, String name, long amount, int typeId, String typeName) {
        this.id = id;
        this.time = time;
        this.name = name;
        this.amount = amount;
        this.typeId = typeId;
        this.typeName = typeName;
    }

    public boolean isIncome() {
        return amount > 0;
    }
}
