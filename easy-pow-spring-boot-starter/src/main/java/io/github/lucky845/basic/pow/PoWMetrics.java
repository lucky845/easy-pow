package io.github.lucky845.basic.pow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PoW 指标暴露:实现 {@link PowDifficultyListener},把签发/校验事件发布成 Micrometer 指标,
 * 使关闭自适应({@code easy-pow.adaptive.enabled=false})时依然能观测。
 *
 * <p>指标(<b>无</b> challenge 标签——challenge 是逐请求的 hex,打标签会撑爆注册表):</p>
 * <ul>
 *   <li>{@code pow.challenge.issued} — 已签发挑战数(Counter)</li>
 *   <li>{@code pow.challenge.verified} — 校验通过数(Counter)</li>
 *   <li>{@code pow.maxnumber} — 当前难度上限(Gauge);未挂载自适应时恒为配置值</li>
 * </ul>
 *
 * <p>本类不采集挑战内容,{@code challengeKey} 入参不进入任何指标标签。</p>
 */
public class PoWMetrics implements PowDifficultyListener {

    /** 签发计数。 */
    public static final String ISSUED = "pow.challenge.issued";
    /** 校验通过计数。 */
    public static final String VERIFIED = "pow.challenge.verified";
    /** 当前难度上限。 */
    public static final String MAXNUMBER = "pow.maxnumber";

    private final Counter issued;
    private final Counter verified;
    private final AdaptiveDifficulty adaptive;
    private final long fallbackMaxnumber;

    /**
     * @param registry 指标注册表
     * @param config   当前配置(未挂载自适应时作为 {@code pow.maxnumber} 的取值)
     * @param adaptive 可选的难度自适应控制器;可为 null
     */
    public PoWMetrics(MeterRegistry registry, PowConfig config, AdaptiveDifficulty adaptive) {
        this.adaptive = adaptive;
        this.fallbackMaxnumber = config.defaultMaxnumber();
        this.issued = Counter.builder(ISSUED).description("已签发的 PoW 挑战数").register(registry);
        this.verified = Counter.builder(VERIFIED).description("校验通过的 PoW 挑战数").register(registry);
        Gauge.builder(MAXNUMBER, this, PoWMetrics::currentMaxnumber)
                .description("当前难度上限(自适应开启时随 EWMA 变化)")
                .register(registry);
    }

    @Override
    public void onIssued(String challengeKey) {
        issued.increment();
    }

    @Override
    public void onVerified(String challengeKey) {
        verified.increment();
    }

    /** 当前难度上限:挂载自适应时取实时值,否则回落配置值。 */
    public double currentMaxnumber() {
        return adaptive == null ? fallbackMaxnumber : adaptive.currentMaxnumber();
    }
}
