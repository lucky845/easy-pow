package io.github.lucky845.basic.pow;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 存储自动配置。
 *
 * <p>只有当调用方自己引入了 {@code spring-boot-starter-data-redis}、容器里也确实存在
 * {@link StringRedisTemplate} 时才会生效。这个类是独立的自动配置,并且用
 * {@code before = PowAutoConfiguration.class} 声明必须先于内存存储求值——
 * 否则 {@link PowAutoConfiguration#powStorage(PowProperties)} 上的
 * {@code @ConditionalOnMissingBean} 会先看到内存实现,把 Redis 实现挤掉。
 *
 * <p>装配优先级(与 easy-captcha 同款模式):
 * <ol>
 *   <li>用户自定义 {@link PowStorage} Bean —— 永远最优先({@code @ConditionalOnMissingBean} 尊重);</li>
 *   <li>容器存在 {@link StringRedisTemplate} —— 注册 {@link RedisPowStorage};</li>
 *   <li>都没有 —— 回落到 {@link PowAutoConfiguration} 的 {@link InMemoryPowStorage}。</li>
 * </ol>
 *
 * @author created by lucky845
 */
@AutoConfiguration(
        before = PowAutoConfiguration.class,
        // 用字符串而不是类字面量:Redis 自动配置不存在时这个类不该因引用链被
        // 类加载拖垮,字符串形式在依赖缺失时安全跳过。
        afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisPowAutoConfiguration {

    /**
     * 检测到 Redis 时,用 Redis 保存挑战记录,替换默认的内存实现。
     */
    @Bean
    @ConditionalOnMissingBean(PowStorage.class)
    public PowStorage powStorage(StringRedisTemplate redisTemplate) {
        return new RedisPowStorage(redisTemplate);
    }
}
