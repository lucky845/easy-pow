package io.github.lucky845.basic.pow;

/**
 * 签发/校验事件观测点(可选挂载),难度自适应的数据来源。
 *
 * <p>两个方法均有默认空实现,按需覆盖;典型实现见 {@link AdaptiveDifficulty}。</p>
 */
public interface PowDifficultyListener {

    /** 挑战已签发:{@code challengeKey} 为 challenge hex。 */
    default void onIssued(String challengeKey) {
    }

    /** 挑战校验通过(仅 PASSED 回调;其余状态走 {@link #onRejected})。 */
    default void onVerified(String challengeKey) {
    }

    /**
     * 校验被拒绝(任何非 {@code PASSED} 状态都会回调,含 {@link PowStatus#STORAGE_ERROR})。
     *
     * <p>默认实现转调 {@link #onVerified},使只关心"通过"的老实现行为不变;
     * 需要按状态分类统计的实现(如指标)应覆盖本方法。</p>
     *
     * @param status 拒绝原因,永不为 {@code PASSED} 且永不为 null
     */
    default void onRejected(String challengeKey, PowStatus status) {
        onVerified(challengeKey);
    }
}
