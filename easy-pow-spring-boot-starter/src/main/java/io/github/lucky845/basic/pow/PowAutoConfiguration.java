package io.github.lucky845.basic.pow;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PoW 自动配置:默认提供内存存储、签发器、校验器三个单例 Bean,
 * 签发器与校验器共享同一个 {@link PowStorage} 实例。
 *
 * <p>每个 Bean 都带 {@link ConditionalOnMissingBean},调用方可以按需替换;
 * 多实例/分布式部署时提供自定义 {@link PowStorage}(如 Redis)即可整体替换存储。
 */
@AutoConfiguration
@EnableConfigurationProperties(PowProperties.class)
// 必须在 MetricsAutoConfiguration 之后:它注册 MeterRegistry,MetricsConfiguration 的
// @ConditionalOnBean(MeterRegistry) 若先求值就找不到 Bean,指标会静默缺失(单元测试传 registry 时看不出来)。
@AutoConfigureAfter(name = "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration")
public class PowAutoConfiguration {

    /** 默认使用内存存储;接入 Redis 时提供自己的 {@link PowStorage} 实现即可覆盖。 */
    @Bean
    @ConditionalOnMissingBean(PowStorage.class)
    public PowStorage powStorage(PowProperties properties) {
        return new InMemoryPowStorage(properties.getMaxPendingChallenges());
    }

    /** 难度自适应控制器;easy-pow.adaptive.enabled=true 时创建,可用自定义 Bean 覆盖。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-pow.adaptive", name = "enabled", havingValue = "true")
    public AdaptiveDifficulty adaptiveDifficulty(PowProperties properties) {
        PowProperties.Adaptive a = properties.getAdaptive();
        return new AdaptiveDifficulty(properties.getMaxNumber(), a.getTargetSolveMs(),
                a.getMinMaxNumber(), a.getMaxMaxNumber(), properties.getTtlMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public PowChallengeIssuer powChallengeIssuer(PowProperties properties, PowStorage storage,
                                                 ObjectProvider<AdaptiveDifficulty> adaptive,
                                                 ObjectProvider<PowDifficultyListener> listeners) {
        PowConfig config = properties.toConfig();
        AdaptiveDifficulty adjuster = properties.getAdaptive().isEnabled() ? adaptive.getIfAvailable() : null;
        PowDifficultyListener listener = composite(adjuster, listeners);
        if (adjuster == null) {
            return new PowChallengeIssuer(config, storage, null, listener);
        }
        return new PowChallengeIssuer(config, storage, adjuster::currentMaxnumber, listener);
    }

    @Bean
    @ConditionalOnMissingBean
    public PowChallengeVerifier powChallengeVerifier(PowProperties properties, PowStorage storage,
                                                     ObjectProvider<AdaptiveDifficulty> adaptive,
                                                     ObjectProvider<PowDifficultyListener> listeners) {
        PowConfig config = properties.toConfig();
        AdaptiveDifficulty adjuster = properties.getAdaptive().isEnabled() ? adaptive.getIfAvailable() : null;
        return new PowChallengeVerifier(config, storage, composite(adjuster, listeners));
    }

    /**
     * Micrometer 在场时注册指标监听器(optional 依赖,不在 classpath 就跳过)。
     *
     * <p>{@code ConditionalOnClass} 必须放在<b>独立嵌套配置类</b>上:写在普通 {@code @Bean} 方法上时,
     * Spring 仍需解析该方法签名里的 {@code MeterRegistry},用户没有 micrometer 时会
     * {@code NoClassDefFoundError},而不是安静跳过。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MetricsConfiguration {

        @Bean
        PoWMetrics poWMetrics(ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry,
                              PowProperties properties,
                              ObjectProvider<AdaptiveDifficulty> adaptive) {
            AdaptiveDifficulty adjuster = properties.getAdaptive().isEnabled() ? adaptive.getIfAvailable() : null;
            return new PoWMetrics(registry.getIfAvailable(), properties.toConfig(), adjuster);
        }
    }

    /**
     * 自适应与用户自定义监听器可能同时存在,合并成一条链。
     * 关闭自适应时仍然挂载其余监听器——观测不该因为关掉自适应而消失。
     */
    private static PowDifficultyListener composite(AdaptiveDifficulty adjuster,
                                                   ObjectProvider<PowDifficultyListener> listeners) {
        java.util.List<PowDifficultyListener> all = new java.util.ArrayList<>();
        if (adjuster != null) {
            all.add(adjuster);
        }
        listeners.orderedStream().forEach(all::add);
        if (all.isEmpty()) {
            return null;
        }
        return all.size() == 1 ? all.get(0) : new PoWListenerComposite(all);
    }
}
