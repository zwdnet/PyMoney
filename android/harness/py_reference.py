#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
数字对拍的参照端：直接调用原程序 tools.py / functions.py 的取数函数，
把它们算出来的量按固定格式打印，和 Java 侧 Compare 的输出做逐字符 diff。

用法：python3 py_reference.py <money.db 路径>
（脚本会把数据库拷到临时目录并 chdir，因为原程序里的 DataBase("money.db") 是硬编码路径）
"""
import math
import os
import shutil
import sqlite3
import sys
import tempfile

SRC = "/home/code/PyMoney"
sys.path.insert(0, SRC)

from tools import (GetSumIncome, GetSumExpense, GetSum, GetSumByTypeID,
                   GetTypeIDbyName)                     # noqa: E402
from functions import Tax, GetItemTotal                                             # noqa: E402
from scipy import stats                                               # noqa: E402

RANGES = [
    (20180101, 20181231),
    (20230101, 20231231),
    (20240301, 20240331),
    (20250101, 20251231),
    (20260101, 20261102),
    (20110101, 20261231),
]


def f(value):
    """和 Java 侧 format() 完全一致的浮点打印"""
    if value is None:
        return "nan"
    return "%.6f" % float(value)


def emit(*parts):
    print("|".join(str(p) for p in parts))


def main():
    if len(sys.argv) < 2:
        print("用法: py_reference.py <money.db>", file=sys.stderr)
        sys.exit(2)
    workdir = tempfile.mkdtemp(prefix="pymoney-ref-")
    shutil.copy(sys.argv[1], os.path.join(workdir, "money.db"))
    os.chdir(workdir)
    con = sqlite3.connect(os.path.join(workdir, "money.db"))

    total, = con.execute("SELECT COUNT(*) FROM Income").fetchone()
    emit("count", total)
    earliest, = con.execute("SELECT MIN(Time) FROM Income WHERE Time > 0").fetchone()
    emit("earliest", int(earliest or 0))
    latest, = con.execute("SELECT MAX(Time) FROM Income").fetchone()
    emit("latest", int(latest or 0))

    type_ids = [row[0] for row in con.execute(
        "SELECT TypeID FROM IncomeType ORDER BY TypeID").fetchall()]

    for start, end in RANGES:
        tag = "%d-%d" % (start, end)
        date = (str(start), str(end))
        emit("income", tag, f(GetSumIncome(date)))
        emit("expense", tag, f(GetSumExpense(date)))
        emit("net", tag, f(GetSum(date)))
        for type_id in type_ids:
            emit("type", tag, type_id, f(GetSumByTypeID(date, type_id)))

        # ---- 税负：分量按 functions.py:Tax() 里的同一串表达式复算，总额直接调原函数 ----
        value_add6 = ("饮食,日常支出,职业发展,通讯,医疗,人际开支,社会活动,婚恋成本,旅行,金融成本,保险,物业费,彩票,"
                      "学车支出,购房支出,文化消费,学习支出,法律咨询费,用车成本_停车费,用车成本_过路费,用车成本_洗车,"
                      "用车成本_维修保养,用车成本_代驾,家电维修,用车成本_保险,物业支出,用车成本_咨询费用")
        value_add9 = "交通,水电,房租,书刊消费"
        value_add13 = ("日常用品,大额消费,亲情消费,自行车爱好,衣物,家电消费,软件消费,用车成本_油耗,用车成本_其它费用,"
                       "用车成本_买配件,电子消费")

        def add_amount(items):
            amount = 0.0
            for item in items.split(","):
                amount += GetItemTotal(start, end, GetTypeIDbyName(item))
            return -1 * amount

        amount6 = add_amount(value_add6)
        amount9 = add_amount(value_add9)
        amount13 = add_amount(value_add13)
        add_value_tax = amount6 * 0.06 + amount9 * 0.09 + amount13 * 0.13
        oil_price = 900
        oil_amount = -1 * GetItemTotal(start, end, GetTypeIDbyName("用车成本_油耗"))
        oil_volume = oil_amount / oil_price
        oil_tax = oil_volume * 152
        city_tax = (add_value_tax + oil_tax) * 0.07
        edu_tax = (add_value_tax + oil_tax) * 0.05
        income_tax = (amount6 + amount9 + amount13) * 0.25
        other_tax = -1 * GetItemTotal(start, end, GetTypeIDbyName("纳税"))
        other_tax += -1 * GetItemTotal(start, end, GetTypeIDbyName("用车成本_税收"))

        emit("tax6", tag, f(amount6))
        emit("tax9", tag, f(amount9))
        emit("tax13", tag, f(amount13))
        emit("vat", tag, f(add_value_tax))
        emit("oilTax", tag, f(oil_tax))
        emit("cityTax", tag, f(city_tax))
        emit("eduTax", tag, f(edu_tax))
        emit("incomeTax", tag, f(income_tax))
        emit("otherTax", tag, f(other_tax))
        total_income = GetSumIncome(date)
        tax_total = Tax(date)          # 原函数，逐字调用
        emit("taxTotal", tag, f(tax_total))
        emit("taxRate", tag, f(math.fabs(tax_total / total_income * 100.0))
             if total_income > 0.0 else "nan")

    # ---- 年度/月度聚合：用 Python 分组，独立验证 Java 侧 Time/10000 那套 SQL ----
    rows = con.execute("SELECT Time, Amount FROM Income").fetchall()
    yearly = {}
    monthly = {}
    for time, amount in rows:
        year_bucket = yearly.setdefault(int(time) // 10000, [0.0, 0.0])
        month_bucket = monthly.setdefault(int(time) // 100, [0.0, 0.0])
        for bucket, value in ((year_bucket, float(amount)), (month_bucket, float(amount))):
            if value > 0:
                bucket[0] += value
            else:
                bucket[1] += value
    for year in sorted(yearly):
        income, expense = yearly[year]
        emit("year", year, f(income), f(expense))
    for month in sorted(monthly):
        if 20120101 <= month * 100 + 1 <= 20261231:
            income, expense = monthly[month]
            emit("month", month, f(income), f(expense))

    # ---- CPI：照 functions.py:CPI() 的算式，年均用 scipy 的 gmean 当参照 ----
    exclude_id = GetTypeIDbyName("给李娅钱")
    cpis = []
    incomes = []
    for year in range(2011 + 1, 2026 + 1):
        date1 = (str(year - 1) + "0101", str(year - 1) + "1231")
        date2 = (str(year) + "0101", str(year) + "1231")
        income1 = GetSumIncome(date1)
        expense1 = GetSumExpense(date1)
        income2 = GetSumIncome(date2)
        expense2 = GetSumExpense(date2)
        expense1 -= GetItemTotal(date1[0], date1[1], exclude_id)
        expense2 -= GetItemTotal(date2[0], date2[1], exclude_id)
        cpi = abs(expense2) / abs(expense1) - 1.0 if abs(expense1) > 0 else float("nan")
        growth = income2 / income1 - 1.0 if income1 != 0 else float("nan")
        cpis.append(cpi)
        incomes.append(growth)
        emit("cpi", year, f(cpi))
        emit("cpiIncome", year, f(growth))

    finite_cpi = [v for v in cpis if not math.isnan(v)]
    finite_income = [v for v in incomes if not math.isnan(v)]
    if finite_cpi:
        min_cpi = min(finite_cpi) - 0.01
        emit("avgCpi", f(stats.gmean([v - min_cpi for v in finite_cpi]) + min_cpi))
    else:
        emit("avgCpi", "nan")
    if finite_income:
        min_income = min(finite_income) - 0.01
        emit("avgIncome", f(stats.gmean([v - min_income for v in finite_income]) + min_income))
    else:
        emit("avgIncome", "nan")

    con.close()
    shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    main()
