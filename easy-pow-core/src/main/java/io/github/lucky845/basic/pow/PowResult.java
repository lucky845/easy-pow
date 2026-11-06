package io.github.lucky845.basic.pow;

/**
 * 校验结果。
 *
 * @param status    结果状态
 * @param reason    结果说明;PASSED 时为 "ok",失败时为具体原因(可直接用于日志)
 * @param challenge payload 中的 challenge hex;payload 无法解析时为 null
 */
public record PowResult(PowStatus status, String reason, String challenge) {

    static PowResult pass(String challenge) {
        return new PowResult(PowStatus.PASSED, "ok", challenge);
    }

    static PowResult fail(PowStatus status, String reason, String challenge) {
        return new PowResult(status, reason, challenge);
    }
}
