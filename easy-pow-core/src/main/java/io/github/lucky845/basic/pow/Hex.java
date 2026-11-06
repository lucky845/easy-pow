package io.github.lucky845.basic.pow;

/** 内部 hex 编解码工具,小写输出。 */
final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    static String encode(byte[] bytes) {
        char[] out = new char[bytes.length << 1];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            out[j++] = DIGITS[(bytes[i] >> 4) & 0x0F];
            out[j++] = DIGITS[bytes[i] & 0x0F];
        }
        return new String(out);
    }

    /** 奇数长度或非法字符抛 {@link IllegalArgumentException}。 */
    static byte[] decode(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("hex 长度必须为偶数: " + len);
        }
        byte[] out = new byte[len >> 1];
        for (int i = 0; i < len; i += 2) {
            out[i >> 1] = (byte) ((digit(hex.charAt(i)) << 4) | digit(hex.charAt(i + 1)));
        }
        return out;
    }

    private static int digit(char c) {
        int v = Character.digit(c, 16);
        if (v < 0) {
            throw new IllegalArgumentException("非法 hex 字符: " + c);
        }
        return v;
    }
}
