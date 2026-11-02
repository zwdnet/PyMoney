package com.pymoney.core;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 密钥信封：库里那几行 k/v 的模型。
 *
 * DEK（32 字节随机数）才是真正加密数据的钥匙，它只待在内存里；
 * 口令派生成 KEK，只用来把 DEK 包住存起来。所以改口令只要重新 wrap 一次，
 * 不用把 12877 条记录重算一遍。
 *
 * 这几行存在新建的 AppCrypto 表里，不碰 Income/IncomeType，
 * 备份文件因此是自描述的：换机、清除数据之后，导入备份 + 口令照样能解开。
 * Python 侧只读那两张业务表，完全无感。
 */
public final class KeyEnvelope {

    public static final String ALGO = "AES-256-GCM/PBKDF2-SHA256";
    public static final int SALT_LENGTH = 16;
    public static final int KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;

    public static final String K_ALGO = "algo";
    public static final String K_ITER = "iter";
    public static final String K_SALT = "salt";
    public static final String K_DEK = "dek";
    public static final String K_VERIFY = "verify";
    /** 可选：一次性恢复码包住的同一把 DEK，抄在纸上那种 */
    public static final String K_RECOVERY = "recovery";
    /** 迁移游标：迁到哪个 ID 了，断电续跑用 */
    public static final String K_CURSOR = "migrate_cursor";

    private static final String VERIFY_PLAIN = "pymoney.key-check.v1";
    private static final byte[] WRAP_AAD = "pymoney.dek-wrap".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VERIFY_AAD = "pymoney.verify".getBytes(StandardCharsets.UTF_8);

    public String algo = ALGO;
    public int iterations = Pbkdf2.DEFAULT_ITERATIONS;
    public byte[] salt;
    /** 用 KEK 包住的 DEK */
    public byte[] wrappedDek;
    /** 用 DEK 加密的固定串，判断口令对不对就用它，不存口令的任何哈希 */
    public byte[] verifier;
    /** 用恢复码派生的钥包住的 DEK，可为空 */
    public byte[] recoveryWrapped;

    private KeyEnvelope() {
    }

    public static KeyEnvelope newEnvelope(String passphrase, String recoveryCode) {
        SecureRandom random = new SecureRandom();
        KeyEnvelope envelope = new KeyEnvelope();
        envelope.salt = new byte[SALT_LENGTH];
        random.nextBytes(envelope.salt);
        byte[] dek = new byte[KEY_LENGTH];
        random.nextBytes(dek);
        envelope.iterations = Pbkdf2.DEFAULT_ITERATIONS;
        envelope.wrappedDek = wrap(kek(passphrase, envelope.salt, envelope.iterations), dek);
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            byte[] body = AesGcmCodec.encrypt(dek, iv, VERIFY_AAD,
                    VERIFY_PLAIN.getBytes(StandardCharsets.UTF_8));
            envelope.verifier = join(iv, body);
            if (recoveryCode != null && recoveryCode.length() > 0) {
                envelope.recoveryWrapped = sealForRecovery(dek, recoveryCode, envelope.iterations);
            }
        } catch (AesGcmCodec.GeneralCryptoException e) {
            throw new IllegalStateException("生成密钥信封失败：" + e, e);
        }
        wipe(dek);
        return envelope;
    }

    /** 口令对不对：解开 DEK 再验那个固定串，两件事都不留下口令的任何痕迹 */
    public byte[] unlock(String passphrase) {
        if (!isComplete() || passphrase == null || passphrase.length() == 0) {
            return null;
        }
        byte[] dek;
        try {
            dek = unwrap(kek(passphrase, salt, iterations), wrappedDek);
        } catch (AesGcmCodec.GeneralCryptoException e) {
            return null;
        } catch (RuntimeException e) {
            // 空口令会走到 HMAC 的 IllegalArgument，库里存的口令也绝不会是空串：
            // 一律当作「不对」，不能让异常穿出对话框
            return null;
        }
        if (dek == null || !checkVerifier(dek)) {
            if (dek != null) {
                wipe(dek);
            }
            return null;
        }
        return dek;
    }

    /** 用恢复码解，走同一套校验 */
    public byte[] unlockRecovery(String recoveryCode) {
        if (recoveryWrapped == null || recoveryWrapped.length <= SALT_LENGTH
                || recoveryCode == null || recoveryCode.length() == 0) {
            return null;
        }
        byte[] codeSalt = new byte[SALT_LENGTH];
        System.arraycopy(recoveryWrapped, 0, codeSalt, 0, SALT_LENGTH);
        byte[] packed = new byte[recoveryWrapped.length - SALT_LENGTH];
        System.arraycopy(recoveryWrapped, SALT_LENGTH, packed, 0, packed.length);
        byte[] dek;
        try {
            dek = unwrap(Pbkdf2.derive(recoveryCode, codeSalt, iterations, KEY_LENGTH), packed);
        } catch (AesGcmCodec.GeneralCryptoException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
        if (dek == null || !checkVerifier(dek)) {
            KeyEnvelope.wipe(dek);
            return null;
        }
        return dek;
    }

    /** 改口令：DEK 不变，只重包一次，数据一个字节都不用动 */
    public void changePassphrase(String oldPassphrase, String newPassphrase) {
        byte[] dek = unlock(oldPassphrase);
        if (dek == null) {
            throw new IllegalStateException("原口令不对");
        }
        SecureRandom random = new SecureRandom();
        byte[] freshSalt = new byte[SALT_LENGTH];
        random.nextBytes(freshSalt);
        salt = freshSalt;
        wrappedDek = wrap(kek(newPassphrase, salt, iterations), dek);
        wipe(dek);
    }

    /**
     * 设置或更换恢复码：口令得对，DEK 不变，库里那些密文一个字节都不动。
     * 换新的之后旧的那张纸立刻作废——它只是同一把 DEK 的另一层包装。
     */
    public void setRecoveryCode(String passphrase, String recoveryCode) {
        byte[] dek = unlock(passphrase);
        if (dek == null) {
            throw new IllegalStateException("口令不对");
        }
        try {
            recoveryWrapped = sealForRecovery(dek, recoveryCode, iterations);
        } finally {
            wipe(dek);
        }
    }

    /** 恢复码和口令走同一套派生参数，不引入第二种强度 */
    private static byte[] sealForRecovery(byte[] dek, String recoveryCode, int iterations) {
        SecureRandom random = new SecureRandom();
        byte[] codeSalt = new byte[SALT_LENGTH];
        random.nextBytes(codeSalt);
        byte[] codeKey = Pbkdf2.derive(recoveryCode, codeSalt, iterations, KEY_LENGTH);
        return join(codeSalt, wrap(codeKey, dek));
    }

    public boolean isComplete() {
        return salt != null && salt.length == SALT_LENGTH
                && wrappedDek != null && wrappedDek.length > IV_LENGTH
                && verifier != null && verifier.length > IV_LENGTH;
    }

    // ---------- 和 AppCrypto 表之间的转换 ----------

    public Map<String, String> toValues() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(K_ALGO, algo);
        rows.put(K_ITER, String.valueOf(iterations));
        rows.put(K_SALT, B64.encode(salt));
        rows.put(K_DEK, B64.encode(wrappedDek));
        rows.put(K_VERIFY, B64.encode(verifier));
        if (recoveryWrapped != null) {
            rows.put(K_RECOVERY, B64.encode(recoveryWrapped));
        }
        return rows;
    }

    /** 缺字段返回 null：这张表要么完整可用，要么当作没开加密 */
    public static KeyEnvelope fromValues(Map<String, String> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        KeyEnvelope envelope = new KeyEnvelope();
        envelope.algo = rows.get(K_ALGO) == null ? ALGO : rows.get(K_ALGO);
        try {
            envelope.iterations = Integer.parseInt(rows.get(K_ITER));
        } catch (Exception e) {
            envelope.iterations = Pbkdf2.DEFAULT_ITERATIONS;
        }
        envelope.salt = B64.decode(rows.get(K_SALT));
        envelope.wrappedDek = B64.decode(rows.get(K_DEK));
        envelope.verifier = B64.decode(rows.get(K_VERIFY));
        envelope.recoveryWrapped = B64.decode(rows.get(K_RECOVERY));
        return envelope.isComplete() ? envelope : null;
    }

    // ---------- 内部 ----------

    private boolean checkVerifier(byte[] dek) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(verifier, 0, iv, 0, IV_LENGTH);
            byte[] body = new byte[verifier.length - IV_LENGTH];
            System.arraycopy(verifier, IV_LENGTH, body, 0, body.length);
            byte[] plain = AesGcmCodec.decrypt(dek, iv, VERIFY_AAD, body);
            return VERIFY_PLAIN.equals(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] kek(String passphrase, byte[] salt, int iterations) {
        return Pbkdf2.derive(passphrase, salt, iterations, KEY_LENGTH);
    }

    private static byte[] wrap(byte[] key, byte[] dek) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            return join(iv, AesGcmCodec.encrypt(key, iv, WRAP_AAD, dek));
        } catch (AesGcmCodec.GeneralCryptoException e) {
            throw new IllegalStateException("包裹数据密钥失败：" + e, e);
        }
    }

    private static byte[] unwrap(byte[] key, byte[] packed)
            throws AesGcmCodec.GeneralCryptoException {
        if (packed == null || packed.length <= IV_LENGTH) {
            throw new AesGcmCodec.GeneralCryptoException(null);
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(packed, 0, iv, 0, IV_LENGTH);
        byte[] body = new byte[packed.length - IV_LENGTH];
        System.arraycopy(packed, IV_LENGTH, body, 0, body.length);
        return AesGcmCodec.decrypt(key, iv, WRAP_AAD, body);
    }

    private static byte[] join(byte[] head, byte[] tail) {
        byte[] out = new byte[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    /** 临时钥匙用完就抹掉。Java 做不到真正确保不残留，但比不抹强 */
    public static void wipe(byte[] bytes) {
        if (bytes != null) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = 0;
            }
        }
    }
}
