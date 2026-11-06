package io.github.lucky845.basic.pow;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * altcha 协议编解码。JSON 为内置极简手工实现(仅扁平对象的双引号字符串与数字字段),零第三方依赖。
 *
 * <p><b>失败语义:</b>所有解析方法失败——非法 base64、非法 JSON、缺少必填字段
 * (algorithm/challenge/number/salt/signature)、number 不是整数——一律返回 {@code null},不抛异常。
 */
public final class AltchaCodec {

    private AltchaCodec() {
    }

    /** 前端 solution 值对象,字段名与 altcha 官方一致;took 为透传标量(数字/字符串),可为 null。 */
    public record Solution(String algorithm, String challenge, long number,
                           String salt, String signature, String took) {
    }

    /** 挑战 → altcha 官方字段 JSON(不含 expiresAt,前端无需感知有效期)。 */
    public static String toJson(PowChallenge challenge) {
        return "{\"algorithm\":" + quote(challenge.algorithm())
                + ",\"challenge\":" + quote(challenge.challenge())
                + ",\"maxnumber\":" + challenge.maxnumber()
                + ",\"salt\":" + quote(challenge.salt())
                + ",\"signature\":" + quote(challenge.signature()) + "}";
    }

    /** 解析 solution,接受 "base64(JSON)" 或原始 JSON 两种输入;失败返回 null(见类注释)。 */
    public static Solution parseSolution(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return null;
        }
        String json = payload.trim();
        if (!json.startsWith("{")) {
            String decoded = tryBase64(json);
            if (decoded == null) {
                return null;
            }
            json = decoded;
        }
        Map<String, Object> fields = parseFlatObject(json);
        if (fields == null) {
            return null;
        }
        Long number = toLong(fields.get("number"));
        String algorithm = asString(fields.get("algorithm"));
        String challenge = asString(fields.get("challenge"));
        String salt = asString(fields.get("salt"));
        String signature = asString(fields.get("signature"));
        if (number == null || algorithm == null || challenge == null || salt == null || signature == null) {
            return null;
        }
        return new Solution(algorithm, challenge, number, salt, signature, asString(fields.get("took")));
    }

    /** 标准 base64 失败再试宽松 MIME 解码;都失败返回 null。 */
    private static String tryBase64(String text) {
        try {
            return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            try {
                return new String(Base64.getMimeDecoder().decode(text), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    /** 标量统一转字符串:数字与字符串字段互相容错;其他类型返回 null。 */
    private static String asString(Object value) {
        if (value instanceof String s) return s;
        if (value instanceof Long || value instanceof Double) return String.valueOf(value);
        return null;
    }

    /** 数字字段容错:接受 JSON 数字或数字字符串;非整数(如 1.5)视为非法返回 null。 */
    private static Long toLong(Object value) {
        if (value instanceof Long l) return l;
        String text = asString(value);
        if (text == null) return null;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(text.trim());
                return d == Math.rint(d) && !Double.isInfinite(d) ? (long) d : null;
            } catch (NumberFormatException alsoNotANumber) {
                return null;
            }
        }
    }

    // ---------------- 极简 JSON(仅扁平对象) ----------------

    /** 解析扁平 JSON 对象,值仅支持字符串与数字;失败返回 null。整数→Long,小数/指数→Double。 */
    static Map<String, Object> parseFlatObject(String json) {
        int[] pos = {0};
        if (!skipWs(json, pos) || json.charAt(pos[0]) != '{') return null;
        pos[0]++;
        Map<String, Object> fields = new LinkedHashMap<>();
        if (skipWs(json, pos) && json.charAt(pos[0]) == '}') return fields;
        while (true) {
            if (!skipWs(json, pos) || json.charAt(pos[0]) != '"') return null;
            String key = parseString(json, pos);
            if (key == null || !skipWs(json, pos) || json.charAt(pos[0]) != ':') return null;
            pos[0]++;
            if (!skipWs(json, pos)) return null;
            char head = json.charAt(pos[0]);
            Object value = head == '"' ? parseString(json, pos)
                    : (head == '-' || (head >= '0' && head <= '9')) ? parseNumber(json, pos) : null;
            if (value == null || !skipWs(json, pos)) return null;
            fields.put(key, value);
            char sep = json.charAt(pos[0]);
            if (sep == ',') {
                pos[0]++;
            } else if (sep == '}') {
                pos[0]++;
                return fields;
            } else {
                return null;
            }
        }
    }

    private static boolean skipWs(String json, int[] pos) {
        while (pos[0] < json.length()) {
            char c = json.charAt(pos[0]);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return true;
            pos[0]++;
        }
        return false;
    }

    /** 双引号字符串,支持双引号、反斜杠、斜杠、b/f/n/r/t 及 uXXXX(unicode)转义;失败返回 null。 */
    private static String parseString(String json, int[] pos) {
        pos[0]++;
        StringBuilder sb = new StringBuilder();
        while (pos[0] < json.length()) {
            char c = json.charAt(pos[0]);
            if (c == '"') { pos[0]++; return sb.toString(); }
            if (c == '\\') {
                pos[0]++;
                if (pos[0] >= json.length()) return null;
                char e = json.charAt(pos[0]);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos[0] + 5 > json.length()) return null;
                        try {
                            sb.append((char) Integer.parseInt(json.substring(pos[0] + 1, pos[0] + 5), 16));
                        } catch (NumberFormatException bad) {
                            return null;
                        }
                        pos[0] += 4;
                    }
                    default -> { return null; }
                }
                pos[0]++;
            } else if (c < 0x20) {
                return null; // 字符串内不允许裸控制字符
            } else {
                sb.append(c);
                pos[0]++;
            }
        }
        return null;
    }

    /** JSON 数字:整数 → Long,小数/指数 → Double;失败返回 null。 */
    private static Object parseNumber(String json, int[] pos) {
        int start = pos[0];
        boolean integral = true;
        while (pos[0] < json.length()) {
            char c = json.charAt(pos[0]);
            if (c >= '0' && c <= '9') {
                pos[0]++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                integral = false;
                pos[0]++;
            } else {
                break;
            }
        }
        String token = json.substring(start, pos[0]);
        try {
            if (integral) return Long.valueOf(token);
            return Double.valueOf(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 手工 JSON 序列化:转义双引号、反斜杠与控制字符。 */
    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
