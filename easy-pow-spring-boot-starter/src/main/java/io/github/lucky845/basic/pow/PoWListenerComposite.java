package io.github.lucky845.basic.pow;

import java.util.List;

/**
 * 把多个 {@link PowDifficultyListener} 串成一条链,任一监听器抛异常都不影响其余监听器,
 * 更不影响签发/校验主流程——观测是旁路,不能反噬业务。
 *
 * <p>SPI 里签发器与校验器各只接收一个监听器,而为开关自适应而独占这个位置会让指标丢失;
 * 本类用来同时挂载自适应与指标。</p>
 */
public final class PoWListenerComposite implements PowDifficultyListener {

    private final List<PowDifficultyListener> delegates;

    public PoWListenerComposite(List<PowDifficultyListener> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void onIssued(String challengeKey) {
        for (PowDifficultyListener d : delegates) {
            try {
                d.onIssued(challengeKey);
            } catch (RuntimeException ignored) {
                // 观测旁路失败不影响签发
            }
        }
    }

    @Override
    public void onVerified(String challengeKey) {
        for (PowDifficultyListener d : delegates) {
            try {
                d.onVerified(challengeKey);
            } catch (RuntimeException ignored) {
                // 观测旁路失败不影响校验结果
            }
        }
    }
}
