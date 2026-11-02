package com.pymoney.core;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 列级 AES-256-GCM 编解码。密文格式：{@code enc:v1:<base64(iv[12] ‖ 密文 ‖ tag[16])>}
 *
 * 前缀是明文，所以「还剩多少没迁」能直接用 SQL 数：WHERE Name NOT LIKE 'enc:v1:%'。
 * 存 TEXT 而不是 BLOB：Name/TypeName 本来就是 NVARCHAR（TEXT affinity），
 * 二进制塞进去会被 SQLite 的 affinity 强转，Termux 的 sqlite3 一打印就成乱码。
 * base64 全 ASCII，Python 那边读回来是个普通字符串。
 *
 * AAD 绑列名，所以把事项名的密文粘到类别名（或别的行）上是解不开的，
 * 这一步让「挪动字段」这种篡改当场失败，而不是悄悄换内容。
 */
public class AesGcmCodec implements Codec {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    /** destroy() 之后这把钥就废了：写出必须抛、读出一律掩码，绝不能拿全零密钥悄悄加密出新数据 */
    private boolean destroyed;

    public AesGcmCodec(byte[] dataKey) {
        if (dataKey == null || dataKey.length != 32) {
            throw new IllegalArgumentException("数据密钥必须是 32 字节");
        }
        key = dataKey.clone();
    }

    @Override
    public boolean encrypted() {
        return true;
    }

    @Override
    public boolean unlocked() {
        return true;
    }

    @Override
    public String seal(String column, String value) {
        if (destroyed) {
            throw new IllegalStateException("这把密钥已作废，请重新输入口令");
        }
        if (value == null || value.length() == 0) {
            return value;                     // 空值没必要加密，也免得库里多一串无意义的密文
        }
        // 这里故意不检查「是不是已经带前缀」：写进库的一律是界面上的明文，
        // 真有人把事项名起成 "enc:v1:xxx"，跳过加密反而会让它读回来变成掩码。
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(column));
            byte[] body = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(body, 0, packed, iv.length, body.length);
            return PREFIX + B64.encode(packed);
        } catch (Exception e) {
            // 加密失败绝不能退化成写明文，宁可让这次写入报错
            throw new IllegalStateException("加密失败：" + e, e);
        }
    }

    @Override
    public String open(String column, String stored) {
        if (destroyed) {
            return Codec.LOCKED_MASK;
        }
        if (!isSealed(stored)) {
            return stored;                     // 混合态：还没迁的明文照常能用
        }
        try {
            byte[] packed = B64.decode(stored.substring(PREFIX.length()));
            if (packed == null || packed.length <= IV_LENGTH) {
                return Codec.LOCKED_MASK;
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(packed, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(column));
            byte[] plain = cipher.doFinal(packed, IV_LENGTH, packed.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 口令不对、字段被挪动、base64 被改坏，都只表现为看不到明文，不抛给界面
            return Codec.LOCKED_MASK;
        }
    }

    /** 锁定/换钥时把这份内存里的密钥抹掉。Java 保证不了绝不残留，但比留着强 */
    public void destroy() {
        destroyed = true;
        KeyEnvelope.wipe(key);
    }

    /** 是不是已加密的值 */
    public static boolean isSealed(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static byte[] aad(String column) {
        return column.getBytes(StandardCharsets.UTF_8);
    }

    /** 供口令校验和密钥包裹复用：同样的 AES-GCM，但 IV 由调用方给 */
    public static byte[] encrypt(byte[] key, byte[] iv, byte[] aad, byte[] plain)
            throws GeneralCryptoException {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(plain);
        } catch (Exception e) {
            throw new GeneralCryptoException(e);
        }
    }

    /** 解不开就抛，调用方拿它判断「口令不对」 */
    public static byte[] decrypt(byte[] key, byte[] iv, byte[] aad, byte[] packed)
            throws GeneralCryptoException {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(packed, 0, packed.length);
        } catch (Exception e) {
            throw new GeneralCryptoException(e);
        }
    }

    /** 只用来把 javax.crypto 的检查异常收拢成一个，免得每个调用点都写一坨 catch */
    public static final class GeneralCryptoException extends Exception {
        GeneralCryptoException(Throwable cause) {
            super(cause == null ? "加解密失败" : cause.toString());
        }
    }
}
