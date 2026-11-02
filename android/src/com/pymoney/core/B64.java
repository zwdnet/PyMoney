package com.pymoney.core;

/**
 * RFC 4648 标准 base64（带 padding）。
 * 为什么不直接用现成的：java.util.Base64 是 API 26，本应用 minSdk 24，装上会 NoClassDefFoundError；
 * android.util.Base64 能在安卓上跑，但 JVM 对拍测试里没有 android.jar，验不了密文格式。
 * 密文格式是要和 Termux 那边的 Python 互通的，所以自己实现一份，两边都能跑同一个测试向量。
 */
public final class B64 {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    /** 反向表：非字符填 -1，遇到就当作非法输入 */
    private static final int[] REVERSE = new int[128];

    static {
        for (int i = 0; i < REVERSE.length; i++) {
            REVERSE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            REVERSE[ALPHABET[i]] = i;
        }
    }

    private B64() {
    }

    public static String encode(byte[] data) {
        if (data == null) {
            return null;
        }
        StringBuilder text = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int block = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            text.append(ALPHABET[(block >> 18) & 63]);
            text.append(ALPHABET[(block >> 12) & 63]);
            text.append(ALPHABET[(block >> 6) & 63]);
            text.append(ALPHABET[block & 63]);
            i += 3;
        }
        int left = data.length - i;
        if (left == 1) {
            int block = (data[i] & 0xFF) << 16;
            text.append(ALPHABET[(block >> 18) & 63]);
            text.append(ALPHABET[(block >> 12) & 63]);
            text.append("==");
        } else if (left == 2) {
            int block = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            text.append(ALPHABET[(block >> 18) & 63]);
            text.append(ALPHABET[(block >> 12) & 63]);
            text.append(ALPHABET[(block >> 6) & 63]);
            text.append('=');
        }
        return text.toString();
    }

    /** 解不开就返回 null，不抛异常：库里可能躺着用户手工改过的值 */
    public static byte[] decode(String text) {
        if (text == null) {
            return null;
        }
        int pad = 0;
        int length = text.length();
        while (length > 0 && text.charAt(length - 1) == '=') {
            length--;
            pad++;
        }
        if (length % 4 == 1) {
            return null;                      // 这个余数在合法 base64 里不可能出现
        }
        // 等号数量必须和剩下的字符数配得上："Zg=" 少一个等号，"Zm9v==" 多填，都算非法
        int wantPad = (4 - length % 4) % 4;
        if (pad != wantPad) {
            return null;
        }
        int estimate = length * 3 / 4;
        byte[] out = new byte[estimate];
        int outIndex = 0;
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            int value = c < 128 ? REVERSE[c] : -1;
            if (value < 0) {
                return null;
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[outIndex++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        if (pad > 2 || outIndex != estimate) {
            return null;
        }
        return out;
    }
}
