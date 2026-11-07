package io.github.lucky845.basic.pow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 内部摘要工具:按算法名计算摘要与 HMAC,输出小写 hex;常量时间字符串比较。
 *
 * <p>支持集与 altcha 前端一致(前端按 challenge 的 algorithm 字段选择实现,
 * 清单经 altcha@2 dist 实证:只有 SHA-256 / SHA-384 / SHA-512,无 SHA3 系列)。
 */
final class Hashes {

    /** 支持的算法名,与 altcha 前端 dist 的清单一致。 */
    static final Set<String> SUPPORTED = Set.of("SHA-256", "SHA-384", "SHA-512");

    private Hashes() {
    }

    static boolean isSupported(String algorithm) {
        return algorithm != null && SUPPORTED.contains(algorithm);
    }

    /** 摘要 hex;算法名须先经 {@link #isSupported(String)} 校验。 */
    static String shaHex(String algorithm, String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return Hex.encode(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(algorithm + " 不可用", e); // 支持集内 JVM 必带,不会发生
        }
    }

    /** HMAC hex(密钥取 UTF-8 字节);算法名须先经 {@link #isSupported(String)} 校验。 */
    static String hmacHex(String algorithm, String secret, String data) {
        String macName = "Hmac" + algorithm.replace("-", ""); // SHA-256 → HmacSHA256
        try {
            Mac mac = Mac.getInstance(macName);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macName));
            return Hex.encode(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(macName + " 不可用", e); // 支持集内 JVM 必带,不会发生
        }
    }

    /** 常量时间比较,防时序侧信道。 */
    static boolean equalsHex(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
