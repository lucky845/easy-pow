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
import org.springframework.context.annotation.Primary;

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

    /** 默认存储的 Bean 名称,与 {@link RedisPowAutoConfiguration} 保持一致:两条路径只留一个主名。 */
    static final String PRIMARY_STORAGE_BEAN = RedisPowAutoConfiguration.PRIMARY_STORAGE_BEAN;

    /** 默认使用内存存储;接入 Redis 时提供自己的 {@link PowStorage} 实现即可覆盖。 */
    // 不用 @ConditionalOnMissingBean:它按"能否解析出 PowStorage"判定,会把用户自定义实现、
    // 以及 Redis 自动配置里别名引用的另一个 PowStorage 都算成"已存在",判定结果随求值顺序漂移。
    // 这里改为按 Bean 名称精确判定:只要存在一个非本自动配置注册的 PowStorage 就退让。
    @Bean(name = PRIMARY_STORAGE_BEAN)
    @Primary
    @ConditionalOnMissingDefaultStorage
    public PowStorage powStorage(PowProperties properties) {
        properties.validate();
        return new InMemoryPowStorage(properties.getMaxPendingChallenges());
    }

    /** 难度自适应控制器;easy-pow.adaptive.enabled=true 时创建,可用自定义 Bean 覆盖。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-pow.adaptive", name = "enabled", havingValue = "true")
    public AdaptiveDifficulty adaptiveDifficulty(PowProperties properties) {
        properties.validate();
        PowProperties.Adaptive a = properties.getAdaptive();
        return new AdaptiveDifficulty(properties.getMaxNumber(), a.getTargetSolveMs(),
                a.getMinMaxNumber(), a.getMaxMaxNumber(), properties.getTtlMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public PowChallengeIssuer powChallengeIssuer(PowProperties properties, ObjectProvider<PowStorage> storages,
                                                 ObjectProvider<AdaptiveDifficulty> adaptive,
                                                 ObjectProvider<PowDifficultyListener> listeners,
                                                 ObjectProvider<PowStorageErrorListener> errorListeners) {
        PowStorage storage = effectiveStorage(storages);
        PowConfig config = properties.toConfig();
        AdaptiveDifficulty adjuster = properties.getAdaptive().isEnabled() ? adaptive.getIfAvailable() : null;
        PowDifficultyListener listener = composite(adjuster, listeners);
        PowStorageErrorListener errorListener = storageErrorComposite(errorListeners);
        if (adjuster == null) {
            return new PowChallengeIssuer(config, storage, null, listener, errorListener);
        }
        return new PowChallengeIssuer(config, storage, adjuster::currentMaxnumber, listener, errorListener);
    }

    @Bean
    @ConditionalOnMissingBean
    public PowChallengeVerifier powChallengeVerifier(PowProperties properties, ObjectProvider<PowStorage> storages,
                                                     ObjectProvider<AdaptiveDifficulty> adaptive,
                                                     ObjectProvider<PowDifficultyListener> listeners,
                                                     ObjectProvider<PowStorageErrorListener> errorListeners) {
        PowStorage storage = effectiveStorage(storages);
        PowConfig config = properties.toConfig();
        AdaptiveDifficulty adjuster = properties.getAdaptive().isEnabled() ? adaptive.getIfAvailable() : null;
        return new PowChallengeVerifier(config, storage, composite(adjuster, listeners),
                storageErrorComposite(errorListeners));
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

        // 这里既不能用 ObjectProvider<PowStorage>.getIfAvailable()(多实现时抛,整个应用起不来),
        // 也不能注入 PowStorage 形参(用户另外声明实现时没有唯一候选)。熔断相关指标本来就只对
        // FailModePowStorage 有意义,直接按这个具体类型取:没有就跳过,有几个也不会让应用起不来。
        @Bean
        PoWMetrics poWMetrics(ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry,
                              PowProperties properties,
                              ObjectProvider<AdaptiveDifficulty> adaptive,
                              ObjectProvider<FailModePowStorage> failModeStorage) {
            AdaptiveDifficulty adjuster = properties.getAdaptive().isEnabled() ? adaptive.getIfAvailable() : null;
            FailModePowStorage failMode = failModeStorage.orderedStream().findFirst().orElse(null);
            return new PoWMetrics(registry.getIfAvailable(), properties.toConfig(), adjuster, failMode);
        }
    }

    /**
     * 取"生效的"存储:有 {@code @Primary} 时它排在最前,否则就是唯一候选。
     *
     * <p>为什么不直接注入 {@code PowStorage} 形参:用户另外声明了实现(例如 fail-mode=OPEN 的
     * 降级候选、或者从别处继承来的测试替身)时容器里就没有唯一候选,Spring 6 把"多个 primary"
     * 也视为无 primary,硬注入会让整个应用起不来 —— 而多候选正是 {@code fallback-storage} 要支持的场景。</p>
     *
     * <p>顺序上"本自动配置注册的那个"排在最前:能走到这里说明默认存储已经创建
     * (内存或 Redis 包装),没有它签发/校验无从谈起;用户声明的候选只可能在
     * {@code fallback-storage} 里被消费。</p>
     */
    private static PowStorage effectiveStorage(ObjectProvider<PowStorage> storages) {
        return storages.orderedStream().findFirst()
                .orElseThrow(() -> new IllegalStateException("没有可用的 PowStorage Bean"));
    }

    /**
     * 存储故障旁路同样允许存在多个(指标 + 用户自己的告警),按注册顺序依次通知;
     * 任何一个抛异常都不影响其余监听器与原异常传播。
     */
    private static PowStorageErrorListener storageErrorComposite(ObjectProvider<PowStorageErrorListener> listeners) {
        java.util.List<PowStorageErrorListener> all = listeners.orderedStream().toList();
        if (all.isEmpty()) {
            return null;
        }
        if (all.size() == 1) {
            return all.get(0);
        }
        return (operation, cause) -> {
            for (PowStorageErrorListener each : all) {
                try {
                    each.onStorageError(operation, cause);
                } catch (RuntimeException ignored) {
                    // 观测是旁路:一个监听器出错不许打断其余的
                }
            }
        };
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
