package io.github.lucky845.basic.pow;

/** 校验结果状态。 */
public enum PowStatus {
    /** 校验通过。 */ PASSED,
    /** 挑战已被使用(防重放命中)。 */ REUSED,
    /** 挑战已过期或无签发记录。 */ EXPIRED,
    /** HMAC 签名不匹配(secret 不一致或字段被篡改)。 */ BAD_SIGNATURE,
    /** solution 无法解析、算法不支持或 challenge 与 salt+number 不匹配。 */ BAD_SOLUTION,
    /** number 超出签发时的 maxnumber。 */ DIFFICULTY_EXCEEDED
}
