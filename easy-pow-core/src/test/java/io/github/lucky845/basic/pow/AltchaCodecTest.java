package io.github.lucky845.basic.pow;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltchaCodecTest {

    private static final String JSON =
            "{\"algorithm\":\"SHA-256\",\"challenge\":\"abc123\",\"number\":12345,\"salt\":\"ff00\",\"signature\":\"deadbeef\",\"took\":12}";

    @Test
    void parsesBase64Json() {
        String base64 = Base64.getEncoder().encodeToString(JSON.getBytes(StandardCharsets.UTF_8));
        AltchaCodec.Solution solution = AltchaCodec.parseSolution(base64);
        assertNotNull(solution);
        assertEquals("SHA-256", solution.algorithm());
        assertEquals(12345, solution.number());
        assertEquals("abc123", solution.challenge());
        assertEquals("ff00", solution.salt());
        assertEquals("deadbeef", solution.signature());
        assertEquals("12", solution.took());
    }

    @Test
    void parsesRawJson() {
        AltchaCodec.Solution solution = AltchaCodec.parseSolution(JSON);
        assertNotNull(solution);
        assertEquals(12345, solution.number());
        assertEquals("abc123", solution.challenge());
    }

    @Test
    void toleratesNumberAsString() {
        String payload = "{\"algorithm\":\"SHA-256\",\"challenge\":\"c\",\"number\":\"999\",\"salt\":\"s\",\"signature\":\"sig\"}";
        AltchaCodec.Solution solution = AltchaCodec.parseSolution(payload);
        assertNotNull(solution);
        assertEquals(999, solution.number());
    }

    @Test
    void rejectsIllegalInput() {
        assertNull(AltchaCodec.parseSolution(null));
        assertNull(AltchaCodec.parseSolution(""));
        assertNull(AltchaCodec.parseSolution("   "));
        assertNull(AltchaCodec.parseSolution("###not-base64-or-json###"));
        assertNull(AltchaCodec.parseSolution("{\"algorithm\":\"SHA-256\"}")); // 缺必填字段
        assertNull(AltchaCodec.parseSolution(
                "{\"algorithm\":\"SHA-256\",\"challenge\":\"c\",\"number\":\"abc\",\"salt\":\"s\",\"signature\":\"x\"}")); // number 非法
        assertNull(AltchaCodec.parseSolution("{\"algorithm\":\"SHA-256\",\"challenge\":\"c\"")); // JSON 截断
    }

    @Test
    void toJsonMatchesAltchaFields() {
        PowChallenge challenge = new PowChallenge("SHA-256", "ab", 1000, "cd", "ef", 123L);
        String json = AltchaCodec.toJson(challenge);
        assertTrue(json.contains("\"algorithm\":\"SHA-256\""));
        assertTrue(json.contains("\"challenge\":\"ab\""));
        assertTrue(json.contains("\"maxnumber\":1000"));
        assertTrue(json.contains("\"salt\":\"cd\""));
        assertTrue(json.contains("\"signature\":\"ef\""));
        Map<String, Object> fields = AltchaCodec.parseFlatObject(json); // 反向解析校验序列化正确性
        assertNotNull(fields);
        assertEquals(5, fields.size());
    }

    @Test
    void hexRoundTrip() {
        byte[] bytes = {0, 1, (byte) 0xAB, (byte) 0xFF};
        assertEquals("0001abff", Hex.encode(bytes));
        assertArrayEquals(bytes, Hex.decode("0001abff"));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("abc")); // 奇数长度
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("zz")); // 非法字符
    }
}
