package com.pymoney.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * PBKDF2-HMAC-SHA256。
 * 不用 SecretKeyFactory("PBKDF2withHmacSHA256")：那个 provider 要 API 26 才保证有，
 * 本应用 minSdk 24。手写也就三十来行，还能和 Python 的 hashlib.pbkdf2_hmac 直接对拍。
 */
public final class Pbkdf2 {

    /** 120000 次在手机上大约 0.2~0.5 秒，只在启动解钥和改口令时跑一次 */
    public static final int DEFAULT_ITERATIONS = 120000;
    /** 一个 HmacSHA256 输出的长度，也是 PBKDF2 的分块大小 */
    private static final int HLEN = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private Pbkdf2() {
    }

    public static byte[] derive(String password, byte[] salt, int iterations, int keyLength) {
        return derive(password == null ? new byte[0]
                : password.getBytes(StandardCharsets.UTF_8), salt, iterations, keyLength);
    }

    public static byte[] derive(byte[] password, byte[] salt, int iterations, int keyLength) {
        Mac mac = keyed(password);
        byte[] out = new byte[keyLength];
        int offset = 0;
        int block = 1;
        while (offset < keyLength) {
            byte[] chunk = block(mac, salt, block, iterations);
            int take = Math.min(HLEN, keyLength - offset);
            System.arraycopy(chunk, 0, out, offset, take);
            offset += take;
            block++;
        }
        return out;
    }

    /** 校验口令：派生出同样的钥再恒定时间比，别把耗时差当 oracle */
    public static boolean matches(String password, byte[] salt, int iterations, byte[] expected) {
        return equalsConstantTime(derive(password, salt, iterations, expected.length), expected);
    }

    private static byte[] block(Mac mac, byte[] salt, int index, int iterations) {
        byte[] seeded = new byte[salt.length + 4];
        System.arraycopy(salt, 0, seeded, 0, salt.length);
        seeded[salt.length] = (byte) (index >>> 24);
        seeded[salt.length + 1] = (byte) (index >>> 16);
        seeded[salt.length + 2] = (byte) (index >>> 8);
        seeded[salt.length + 3] = (byte) index;
        byte[] u = mac.doFinal(seeded);
        byte[] result = u.clone();
        for (int i = 1; i < iterations; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < HLEN; j++) {
                result[j] ^= u[j];
            }
        }
        return result;
    }

    /** Mac 里有口令，绝不能做成共享的静态实例 */
    private static Mac keyed(byte[] password) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(password, ALGORITHM));
            return mac;
        } catch (Exception e) {
            throw new IllegalStateException("这台机器上没有 " + ALGORITHM + "：" + e, e);
        }
    }

    public static boolean equalsConstantTime(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
