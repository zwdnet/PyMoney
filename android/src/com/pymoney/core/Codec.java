package com.pymoney.core;

/**
 * 库里那些「只有 App 能看懂」的字段，进出数据库都要过它。
 * 只有文字（事项名、类别名）走这里；时间、金额、类型 ID 一律明文，
 * 这样所有 SQL 聚合、图表、税负、CPI 在加密之后仍然逐值成立。
 *
 * 约定：seal/open 都必须幂等且不抛异常。迁移跑到一半被杀进程，库里就是
 * 一半明文一半密文的混合态，App 照样能正常读写。
 */
public interface Codec {

    /** AAD 绑列名：把事项名的密文搬到类别名列上，解密会直接失败 */
    String INCOME_NAME = "Income.Name";
    String TYPE_NAME = "IncomeType.TypeName";

    /** 未解锁时给界面看的占位文字，不含任何原文 */
    String LOCKED_MASK = "🔒 已加密";

    /** 写库前调用：一律把明文封成 enc:v1:...（不看前缀，免得用户自己起的同名值被当成密文） */
    String seal(String column, String value);

    /** 读库后调用：不是密文的原样返回，是密文就解开；解不开返回掩码，绝不抛 */
    String open(String column, String stored);

    /** 这个编解码器会不会真的加密（明文态返回 false） */
    boolean encrypted();

    /** 当前是不是「知道密钥」的状态 */
    boolean unlocked();
}
