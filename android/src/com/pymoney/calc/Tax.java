package com.pymoney.calc;

import com.pymoney.db.AggQuery;
import com.pymoney.db.TypeDao;

/**
 * 个人税负估算，逐条照搬 functions.py 的 Tax()。
 * 三档增值税的中文类型名单是原程序硬编码的，这里原样保留，改了就和历史数据对不上。
 * 所有金额单位都是「分」，最后展示时才除以 100 —— 原程序就是这么混用的。
 */
public class Tax {

    /** 结果，单位分。double 是因为要乘税率。 */
    public static class Result {
        public double vatBase6;
        public double vatBase9;
        public double vatBase13;
        public double vat;
        public double oilTax;
        public double cityTax;
        public double eduTax;
        public double incomeTax;
        public double otherTax;
        public double total;
        public double rate = Double.NaN;   // 税负率 %，没有收入时不算
        public double totalIncome;
    }

    private static final String VAT_6 =
            "饮食,日常支出,职业发展,通讯,医疗,人际开支,社会活动,婚恋成本,旅行,金融成本,保险,物业费,彩票,"
                    + "学车支出,购房支出,文化消费,学习支出,法律咨询费,用车成本_停车费,用车成本_过路费,用车成本_洗车,"
                    + "用车成本_维修保养,用车成本_代驾,家电维修,用车成本_保险,物业支出,用车成本_咨询费用";

    private static final String VAT_9 = "交通,水电,房租,书刊消费";

    private static final String VAT_13 =
            "日常用品,大额消费,亲情消费,自行车爱好,衣物,家电消费,软件消费,用车成本_油耗,用车成本_其它费用,"
                    + "用车成本_买配件,电子消费";

    /** 原程序的假设：油价 9 元/升，燃油税 1.52 元/升 */
    private static final double OIL_PRICE_FEN = 900;
    private static final double OIL_TAX_PER_LITER = 152;

    private final AggQuery agg;
    private final TypeDao types;

    public Tax(AggQuery agg, TypeDao types) {
        this.agg = agg;
        this.types = types;
    }

    /**
     * 区间内的税负估算。
     * 类型名查不到时按 0 计入（原程序会拼出 TypeID = None 而报错，实际库里这些类型都存在）。
     */
    public Result compute(int start, int end) {
        Result result = new Result();
        result.vatBase6 = taxableAmount(start, end, VAT_6);
        result.vatBase9 = taxableAmount(start, end, VAT_9);
        result.vatBase13 = taxableAmount(start, end, VAT_13);

        result.vat = result.vatBase6 * 0.06 + result.vatBase9 * 0.09 + result.vatBase13 * 0.13;

        double oilAmount = itemTotalPositive(start, end, "用车成本_油耗");
        double oilVolume = oilAmount / OIL_PRICE_FEN;
        result.oilTax = oilVolume * OIL_TAX_PER_LITER;

        result.cityTax = (result.vat + result.oilTax) * 0.07;
        result.eduTax = (result.vat + result.oilTax) * 0.05;
        result.incomeTax = (result.vatBase6 + result.vatBase9 + result.vatBase13) * 0.25;

        result.otherTax = itemTotalPositive(start, end, "纳税")
                + itemTotalPositive(start, end, "用车成本_税收");

        result.total = result.vat + result.oilTax + result.cityTax + result.eduTax
                + result.incomeTax + result.otherTax;

        result.totalIncome = agg.sumIncome(start, end);
        if (result.totalIncome > 0.0) {
            result.rate = Math.abs(result.total / result.totalIncome * 100.0);
        }
        return result;
    }

    /** 一组类型在区间内的支出总额，取正数（对应 getAddAmount） */
    private double taxableAmount(int start, int end, String nameList) {
        double amount = 0.0;
        for (String name : nameList.split(",")) {
            amount += signedTotal(start, end, name.trim());
        }
        return -amount;
    }

    private double signedTotal(int start, int end, String typeName) {
        Integer typeId = types.idByName(typeName);
        if (typeId == null) {
            return 0.0;
        }
        return agg.sumByType(start, end, typeId);
    }

    private double itemTotalPositive(int start, int end, String typeName) {
        return -signedTotal(start, end, typeName);
    }
}
