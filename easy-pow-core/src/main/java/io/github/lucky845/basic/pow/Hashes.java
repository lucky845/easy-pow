package io.github.lucky845.basic.pow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 内部摘要工具:SHA-256 / HMAC-SHA256,输出小写 hex;常量时间字符串比较。 */
final class Hashes {

    static final String ALGORITHM = "SHA-256";

    private Hashes() {
    }

    static String sha256Hex(String data) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e); // JVM 必带,不会发生
        }
    }

    static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Hex.encode(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e); // JVM 必带,不会发生
        }
    }

    /** 常量时间比较,防时序侧信道。 */
    static boolean equalsHex(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
