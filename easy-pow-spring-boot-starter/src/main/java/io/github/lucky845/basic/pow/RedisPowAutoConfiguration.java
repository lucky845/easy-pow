package io.github.lucky845.basic.pow;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 存储自动配置。
 *
 * <p>只有当调用方自己引入了 {@code spring-boot-starter-data-redis}、容器里也确实存在
 * {@link StringRedisTemplate} 时才会生效。这个类是独立的自动配置,并且用
 * {@code before = PowAutoConfiguration.class} 声明先于内存存储求值。
 *
 * <p>{@code @ConditionalOnMissingBean(PowStorage.class)} 必须放在**类**上而不是 Bean 方法上:
 * 方法级条件在 {@link AutoConfiguration} 之间按"后定义者赢"决定注册,而 {@code before}
 * 只影响排序不影响胜负,两种写法叠在一起会出现谁都没被注册、最终注入内存实现的情况。
 * 类级条件在解析阶段求值,语义是"容器里没有用户自己声明的存储才上场",与 Redis 先于
 * 内存存储求值的顺序配合即可确定结果。
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
@ConditionalOnMissingBean(PowStorage.class)
public class RedisPowAutoConfiguration {

    /**
     * 检测到 Redis 时,用 Redis 保存挑战记录,替换默认的内存实现。
     *
     * <p>外面套一层 {@link FailModePowStorage}:Redis 是网络依赖,一次抖动不该让校验接口
     * 既不 fail-closed 也不 fail-open,而是抛 500。故障时怎么取舍交给 {@code easy-pow.storage-fail-mode}。</p>
     *
     * <p>{@code @ConditionalOnMissingBean} 已提升到类级,这里不再重复声明。</p>
     */
    @Bean
    public PowStorage powStorage(StringRedisTemplate redisTemplate,
                                 PowProperties properties,
                                 ObjectProvider<PowStorage> storageProvider) {
        RedisPowStorage redis = new RedisPowStorage(redisTemplate, java.time.Clock.systemUTC(),
                properties.getRedisKeyPrefix());
        PowStorageFailMode mode = properties.getStorageFailMode();
        PowStorage fallback = null;
        if (mode == PowStorageFailMode.OPEN) {
            fallback = resolveFallback(properties, storageProvider);
        }
        return new FailModePowStorage(redis, fallback, mode, properties.getStorageCooldownMs());
    }

    /**
     * 解析降级存储:优先按 {@code fallback-storage} 指定的 Bean 名称取,
     * 否则取容器里除 Redis/装饰器之外的任意一个;都没有则退化为进程内存储。
     *
     * <p>进程内降级只在单实例下有防重放能力,多实例会退化为"无防重放",
     * 这是 {@code fail-mode: OPEN} 的既定代价,已在 {@link PowStorageFailMode#OPEN} 写明。</p>
     */
    private static PowStorage resolveFallback(PowProperties properties, ObjectProvider<PowStorage> storageProvider) {
        String name = properties.getFallbackStorage();
        if (name != null && !name.isBlank()) {
            PowStorage named = storageProvider.orderedStream()
                    .filter(s -> !(s instanceof FailModePowStorage) && !(s instanceof RedisPowStorage))
                    .filter(s -> name.equals(resolveBeanName(s)))
                    .findFirst()
                    .orElse(null);
            if (named != null) {
                return named;
            }
        }
        return storageProvider.orderedStream()
                .filter(s -> !(s instanceof FailModePowStorage) && !(s instanceof RedisPowStorage))
                .findFirst()
                .orElseGet(() -> new InMemoryPowStorage(properties.getMaxPendingChallenges()));
    }

    /**
     * 尽力取 Bean 名称;拿不到(如普通对象)时返回其类名,使按名称匹配在非容器场景下也能工作。
     */
    private static String resolveBeanName(PowStorage storage) {
        return storage.getClass().getSimpleName();
    }
}
