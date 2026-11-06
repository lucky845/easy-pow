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

    /** 挑战校验通过(仅 PASSED 回调;过期/重放/签名失败不回调)。 */
    default void onVerified(String challengeKey) {
    }
}
