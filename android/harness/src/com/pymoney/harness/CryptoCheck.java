package com.pymoney.harness;

import com.pymoney.core.AesGcmCodec;
import com.pymoney.core.B64;
import com.pymoney.core.Codec;
import com.pymoney.core.KeyEnvelope;
import com.pymoney.core.LockedCodec;
import com.pymoney.core.Pbkdf2;
import com.pymoney.core.PlainCodec;
import com.pymoney.core.Sql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * 加密核心的 JVM 自检。跑在真库副本上，
 * 目的是把「Java 写的密文 Python 解得开」这件事在构建机上就证一遍，而不是等手机。
 */
public final class CryptoCheck {

    private static int passed;
    private static int failed;

    private CryptoCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: CryptoCheck <money.db> [向量输出路径]");
            System.exit(2);
        }
        checkB64();
        checkPbkdf2();
        Sql sql = new JdbcSql(args[0]);
        try {
            checkB64AgainstLibrary(sql);
            checkCodec(sql);
        } finally {
            sql.close();
        }
        checkEnvelope();
        checkMixedState();
        if (args.length > 1) {
            writeVectors(args[1]);
        }
        System.out.println("done|" + passed + "|" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------- base64 ----------

    private static void checkB64() {
        String[][] vectors = {
                {"", ""},
                {"f", "Zg=="},
                {"fo", "Zm8="},
                {"foo", "Zm9v"},
                {"foob", "Zm9vYg=="},
                {"fooba", "Zm9vYmE="},
                {"foobar", "Zm9vYmFy"},
        };
        for (String[] vector : vectors) {
            byte[] bytes = vector[0].getBytes(StandardCharsets.UTF_8);
            expect("B64|编码|" + vector[0], vector[1], B64.encode(bytes));
            expect("B64|解码|" + vector[1], vector[0],
                    new String(zero(B64.decode(vector[1])), StandardCharsets.UTF_8));
        }
        // 全字节值域：0..255 每个都走一遍往返
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        expect("B64|全字节往返", "yes",
                java.util.Arrays.equals(all, B64.decode(B64.encode(all))) ? "yes" : "no");
        expect("B64|非法输入不抛", "null", String.valueOf(B64.decode("Zg=")));
        expect("B64|长度模 4 余 1 非法", "null", String.valueOf(B64.decode("abcde")));
        expect("B64|过度填充非法", "null", String.valueOf(B64.decode("Zm9v==")));
        expect("B64|表外字符非法", "null", String.valueOf(B64.decode("Zm9*")));
        expect("B64|空串解成零字节", "0", String.valueOf(B64.decode("").length));
    }

    private static byte[] zero(byte[] bytes) {
        return bytes == null ? new byte[0] : bytes;
    }

    /** 库里的 Name 全部走一遍 base64 往返，顺带证明没有哪个中文名会踩到编码边界 */
    private static void checkB64AgainstLibrary(Sql sql) {
        List<Object[]> rows = sql.select("SELECT Name FROM Income ORDER BY ID");
        int bad = 0;
        for (Object[] row : rows) {
            String name = row[0] == null ? "" : String.valueOf(row[0]);
            byte[] raw = name.getBytes(StandardCharsets.UTF_8);
            if (!java.util.Arrays.equals(raw, B64.decode(B64.encode(raw)))) {
                bad++;
            }
        }
        expect("B64|库内 " + rows.size() + " 条往返", "0", String.valueOf(bad));
    }

    // ---------- PBKDF2 ----------

    private static void checkPbkdf2() {
        expect("Pbkdf2|password/salt/1", "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
                hex(Pbkdf2.derive("password", bytes("salt"), 1, 32)));
        expect("Pbkdf2|password/salt/2", "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
                hex(Pbkdf2.derive("password", bytes("salt"), 2, 32)));
        expect("Pbkdf2|password/salt/4096", "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
                hex(Pbkdf2.derive("password", bytes("salt"), 4096, 32)));
        // 中文口令 + UTF-8 字节序要和 Python 一致
        expect("Pbkdf2|中文口令", "2c99ecb7d991d8cbfaf4e22aac41325e98eb590c893ff8aa1cb86ca4600d1578",
                hex(Pbkdf2.derive("pymoney口令测试", bytes("saltvalue"), 10000, 32)));
        // 64 字节要跨两个 PBKDF2 分块，专门盯 block 2 的序号编码
        expect("Pbkdf2|64 字节跨两块",
                "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
                        + "4dbf3a2f3dad3377264bb7b8e8330d4efc7451418617dabef683735361cdc18c",
                hex(Pbkdf2.derive("password", bytes("salt"), 1, 64)));
        // 120000 次是真实用量，顺带确认 iterations 没写死在别处
        expect("Pbkdf2|120000 次",
                "e7c5dd58a138ebd40577e3888294935e0e56b3d7618cc4fa8f732600e2c49e5f",
                hex(Pbkdf2.derive("passphrase　with-space", bytes("0123456789abcdef"),
                        Pbkdf2.DEFAULT_ITERATIONS, 32)));
        expect("Pbkdf2|matches 正确口令", "true",
                String.valueOf(Pbkdf2.matches("password", bytes("salt"), 1,
                        Pbkdf2.derive("password", bytes("salt"), 1, 32))));
        expect("Pbkdf2|matches 错误口令", "false",
                String.valueOf(Pbkdf2.matches("password2", bytes("salt"), 1,
                        Pbkdf2.derive("password", bytes("salt"), 1, 32))));
    }

    // ---------- 列级编解码 ----------

    private static void checkCodec(Sql sql) {
        byte[] dek = fixedKey();
        AesGcmCodec codec = new AesGcmCodec(dek);
        List<Object[]> rows = sql.select("SELECT Name FROM Income ORDER BY ID");
        String longest = "";
        for (Object[] row : rows) {
            String name = row[0] == null ? "" : String.valueOf(row[0]);
            if (name.length() > longest.length()) {
                longest = name;
            }
        }
        String[] samples = {"", "早", "早餐", longest, "enc:v1:", Codec.LOCKED_MASK,
                "a<b>&\"'\\", "￥1000.00"};
        for (String sample : samples) {
            String sealed = codec.seal(Codec.INCOME_NAME, sample);
            if (sample.length() == 0) {
                expect("Codec|空串不加密", "0", String.valueOf(sealed.length()));
                continue;
            }
            expect("Codec|前缀|" + sample, "true",
                    String.valueOf(sealed.startsWith(AesGcmCodec.PREFIX)));
            expect("Codec|往返|" + sample, sample, codec.open(Codec.INCOME_NAME, sealed));
        }
        // 自我伪装：明文里就写着 enc:v1: 的值也要能原样往返（封存时不看前缀，一律加密）
        String disguised = "enc:v1:aaaa";
        String sealedOnce = codec.seal(Codec.INCOME_NAME, disguised);
        String sealedTwice = codec.seal(Codec.INCOME_NAME, sealedOnce);
        expect("Codec|伪装值往返", disguised, codec.open(Codec.INCOME_NAME, sealedOnce));
        expect("Codec|解一层露一层", sealedOnce, codec.open(Codec.INCOME_NAME, sealedTwice));

        // tag 翻一个 bit 必须失败
        byte[] packed = B64.decode(codec.seal(Codec.INCOME_NAME, "早餐 -300").substring(
                AesGcmCodec.PREFIX.length()));
        packed[packed.length - 1] ^= 0x01;
        expect("Codec|tag 篡改→掩码", Codec.LOCKED_MASK,
                codec.open(Codec.INCOME_NAME, AesGcmCodec.PREFIX + B64.encode(packed)));

        // AAD 绑列名：拿事项名的密文当类别名解，必须失败
        String asName = codec.seal(Codec.INCOME_NAME, "打车费");
        expect("Codec|换列名→掩码", Codec.LOCKED_MASK, codec.open(Codec.TYPE_NAME, asName));

        // 换一把钥也必须是掩码，不是乱码
        AesGcmCodec other = new AesGcmCodec(otherKey());
        expect("Codec|错钥→掩码", Codec.LOCKED_MASK, other.open(Codec.INCOME_NAME, asName));

        // IV 每次随机：同一个明文封两次不能得到同一段密文（否则能按密文相同反推「这笔和那笔同名」）
        expect("Codec|IV 随机", "false",
                String.valueOf(codec.seal(Codec.INCOME_NAME, "早餐")
                        .equals(codec.seal(Codec.INCOME_NAME, "早餐"))));

        // 没前缀的明文原样穿过，混合态才成立
        expect("Codec|明文直通", "老数据", codec.open(Codec.INCOME_NAME, "老数据"));
        expect("Codec|封存 null", "null", String.valueOf(codec.seal(Codec.INCOME_NAME, null)));
        expect("Codec|解开 null", "null", String.valueOf(codec.open(Codec.INCOME_NAME, null)));
        // base64 被人为改坏也不能抛
        expect("Codec|坏 base64→掩码", Codec.LOCKED_MASK,
                codec.open(Codec.INCOME_NAME, AesGcmCodec.PREFIX + "!!!!"));

        // 全库逐条：封了再解，和原文逐条相同
        int bad = 0;
        for (Object[] row : rows) {
            String name = row[0] == null ? "" : String.valueOf(row[0]);
            if (!name.equals(codec.open(Codec.INCOME_NAME, codec.seal(Codec.INCOME_NAME, name)))) {
                bad++;
            }
        }
        expect("Codec|全库 " + rows.size() + " 条往返", "0", String.valueOf(bad));
    }

    // ---------- 密钥信封 ----------

    private static void checkEnvelope() {
        String passphrase = "测试口令-1";
        String recovery = "RECOV-1234-ABCD";
        KeyEnvelope envelope = KeyEnvelope.newEnvelope(passphrase, recovery);
        expect("Envelope|完整", "true", String.valueOf(envelope.isComplete()));
        expect("Envelope|口令错", "null", String.valueOf(envelope.unlock("错的口令")));
        byte[] dek = envelope.unlock(passphrase);
        expect("Envelope|口令对", "32", dek == null ? "null" : String.valueOf(dek.length));
        byte[] byCode = envelope.unlockRecovery(recovery);
        expect("Envelope|恢复码同钥", "true",
                String.valueOf(Pbkdf2.equalsConstantTime(dek, byCode)));
        expect("Envelope|恢复码错", "null", String.valueOf(envelope.unlockRecovery("NOPE")));
        // 界面上一个字符都不输就点「解锁」：只能当作口令不对，绝不能抛出去把进程带走
        expect("Envelope|空口令→null", "null", String.valueOf(envelope.unlock("")));
        expect("Envelope|null 口令→null", "null", String.valueOf(envelope.unlock(null)));
        expect("Envelope|空恢复码→null", "null", String.valueOf(envelope.unlockRecovery("")));

        byte[] original = dek.clone();
        byte[] saltBefore = envelope.salt.clone();
        envelope.changePassphrase("测试口令-1", "新口令-2");
        boolean saltChanged = !java.util.Arrays.equals(saltBefore, envelope.salt);
        expect("Envelope|旧口令失效", "null", String.valueOf(envelope.unlock(passphrase)));
        byte[] after = envelope.unlock("新口令-2");
        expect("Envelope|改口令不换 DEK", "true",
                String.valueOf(Pbkdf2.equalsConstantTime(original, after)));
        expect("Envelope|改口令换 salt", "true", String.valueOf(saltChanged));

        java.util.Map<String, String> rows = envelope.toValues();
        KeyEnvelope revived = KeyEnvelope.fromValues(rows);
        expect("Envelope|存取往返", "true", String.valueOf(
                revived != null && Pbkdf2.equalsConstantTime(
                        revived.unlock("新口令-2"), original)));
        rows.remove(KeyEnvelope.K_VERIFY);
        expect("Envelope|缺字段→null", "null", String.valueOf(KeyEnvelope.fromValues(rows)));
        KeyEnvelope.wipe(original);
        KeyEnvelope.wipe(dek);
    }

    /** 未解锁 / 混合态：读取一律不抛，写出必须拒绝 */
    private static void checkMixedState() {
        LockedCodec locked = new LockedCodec();
        expect("Locked|明文直通", "老数据", locked.open(Codec.INCOME_NAME, "老数据"));
        expect("Locked|密文→掩码", Codec.LOCKED_MASK,
                locked.open(Codec.INCOME_NAME, AesGcmCodec.PREFIX + "AAAA"));
        boolean threw = false;
        try {
            locked.seal(Codec.INCOME_NAME, "新记的一");
        } catch (IllegalStateException e) {
            threw = true;
        }
        expect("Locked|未解锁禁写", "true", String.valueOf(threw));
        expect("Locked|状态", "false|true",
                locked.unlocked() + "|" + locked.encrypted());
        PlainCodec plain = new PlainCodec();
        expect("Plain|直通", "早餐", plain.open(Codec.INCOME_NAME, "早餐"));
        expect("Plain|不加密", "", plain.seal(Codec.INCOME_NAME, "早餐").replace("早餐", ""));
    }

    // ---------- 给 Python 交叉验证用的固定向量 ----------

    private static void writeVectors(String path) throws IOException {
        byte[] key = fixedKey();
        byte[] iv = new byte[12];
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) (i + 1);
        }
        StringBuilder text = new StringBuilder();
        text.append("algo|").append(KeyEnvelope.ALGO).append('\n');
        String[] b64Plains = {"foobar", "早餐 -300", ""};
        for (String plain : b64Plains) {
            // 字段：明文 | Java 编出来的结果，Python 拿明文自己编一遍对
            text.append("b64|").append(plain).append('|')
                    .append(B64.encode(plain.getBytes(StandardCharsets.UTF_8))).append('\n');
        }
        text.append("pbkdf2|password|salt|1|32|")
                .append(hex(Pbkdf2.derive("password", bytes("salt"), 1, 32))).append('\n');
        text.append("pbkdf2|pymoney口令测试|saltvalue|10000|32|")
                .append(hex(Pbkdf2.derive("pymoney口令测试", bytes("saltvalue"), 10000, 32)))
                .append('\n');
        text.append("pbkdf2|passphrase\u3000with-space|0123456789abcdef|120000|32|")
                .append(hex(Pbkdf2.derive("passphrase　with-space", bytes("0123456789abcdef"),
                        120000, 32))).append('\n');
        String[] plains = {"早餐 -300", "给李娅钱", "a", "", "给 李 娅  钱 ￥1,000.00"};
        for (String plain : plains) {
            try {
                byte[] body = AesGcmCodec.encrypt(key, iv,
                        Codec.INCOME_NAME.getBytes(StandardCharsets.UTF_8),
                        plain.getBytes(StandardCharsets.UTF_8));
                byte[] packed = new byte[iv.length + body.length];
                System.arraycopy(iv, 0, packed, 0, iv.length);
                System.arraycopy(body, 0, packed, iv.length, body.length);
                text.append("gcm|").append(hex(key)).append('|').append(hex(iv))
                        .append('|').append(Codec.INCOME_NAME).append('|')
                        .append(B64.encode(plain.getBytes(StandardCharsets.UTF_8)))
                        .append('|').append(B64.encode(packed)).append('\n');
            } catch (AesGcmCodec.GeneralCryptoException e) {
                System.out.println("FAIL|向量生成|" + e);
            }
        }
        Files.write(java.nio.file.Paths.get(path),
                text.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("ok|向量|" + path);
    }

    // ---------- 工具 ----------

    private static byte[] fixedKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 3);
        }
        return key;
    }

    /** 和 fixedKey 只差最后一字节，专门用来验「错钥必须失败」而不是碰巧等于原钥 */
    private static byte[] otherKey() {
        byte[] key = fixedKey();
        key[key.length - 1] ^= 0x01;
        return key;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte b : data) {
            text.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return text.toString();
    }

    private static void expect(String label, String want, String got) {
        if (want.equals(got)) {
            passed++;
            System.out.println("ok|" + label + "|" + got);
        } else {
            failed++;
            System.out.println("FAIL|" + label + "| 期望 " + want + " 实得 " + got);
        }
    }

}
