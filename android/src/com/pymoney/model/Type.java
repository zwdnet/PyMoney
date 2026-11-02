package com.pymoney.model;

/** 一个项目类型，对应表 IncomeType */
public class Type {
    public int id;
    public String name;
    public long count;     // 该类型下的记录数，列表页用于提示能否删除
    public long amount;    // 该类型下的金额合计（分）

    public Type() {
    }

    public Type(int id, String name) {
        this.id = id;
        this.name = name;
    }
}
