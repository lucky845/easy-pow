package io.github.lucky845.basic.pow;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * PoW 自动配置:默认提供内存存储、签发器、校验器三个单例 Bean,
 * 签发器与校验器共享同一个 {@link PowStorage} 实例。
 *
 * <p>每个 Bean 都带 {@link ConditionalOnMissingBean},调用方可以按需替换;
 * 多实例/分布式部署时提供自定义 {@link PowStorage}(如 Redis)即可整体替换存储。
 */
@AutoConfiguration
@EnableConfigurationProperties(PowProperties.class)
public class PowAutoConfiguration {

    /** 默认使用内存存储;接入 Redis 时提供自己的 {@link PowStorage} 实现即可覆盖。 */
    @Bean
    @ConditionalOnMissingBean(PowStorage.class)
    public PowStorage powStorage() {
        return new InMemoryPowStorage();
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
                                                 ObjectProvider<AdaptiveDifficulty> adaptive) {
        AdaptiveDifficulty adjuster = adaptive.getIfAvailable();
        if (adjuster == null || !properties.getAdaptive().isEnabled()) {
            return new PowChallengeIssuer(properties.toConfig(), storage);
        }
        return new PowChallengeIssuer(properties.toConfig(), storage, adjuster::currentMaxnumber, adjuster);
    }

    @Bean
    @ConditionalOnMissingBean
    public PowChallengeVerifier powChallengeVerifier(PowProperties properties, PowStorage storage,
                                                     ObjectProvider<AdaptiveDifficulty> adaptive) {
        AdaptiveDifficulty adjuster = adaptive.getIfAvailable();
        if (adjuster == null || !properties.getAdaptive().isEnabled()) {
            return new PowChallengeVerifier(properties.toConfig(), storage);
        }
        return new PowChallengeVerifier(properties.toConfig(), storage, adjuster);
    }
}
