package io.github.lucky845.basic.pow;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 存储自动配置。
 *
 * <p>只有当调用方自己引入了 {@code spring-boot-starter-data-redis}、容器里也确实存在
 * {@link StringRedisTemplate} 时才会生效。这个类是独立的自动配置,并且用
 * {@code before = PowAutoConfiguration.class} 声明先于内存存储求值。
 *
 * <p>条件用自写的 {@link ConditionalOnMissingDefaultStorage} 而不是 {@code @ConditionalOnMissingBean}:
 * 后者在解析阶段按"当前能否解析出 PowStorage"判定,而本配置与 {@code PowAutoConfiguration}
 * 互相 {@code before}、又都注册同类型 Bean,判定结果会随求值顺序漂移 —— 实测出现
 * "条件通过、Bean 却根本没注册"的情况。{@link ConditionalOnMissingDefaultStorage} 同时看
 * 已注册类型和 BeanDefinitionRegistry 里声明的工厂方法返回类型,两种时机都能给出稳定答案。
 *
 * <p>装配优先级(与 easy-captcha 同款模式):
 * <ol>
 *   <li>用户自定义 {@link PowStorage} Bean —— 永远最优先;用户一旦声明,本配置整体让路。</li>
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
// PowStorage 与 StringRedisTemplate 一起声明:前者是 core 的契约类,缺了本配置无从谈起。
@ConditionalOnClass({StringRedisTemplate.class, PowStorage.class})
@ConditionalOnBean(type = "org.springframework.data.redis.core.StringRedisTemplate")
@ConditionalOnMissingDefaultStorage
public class RedisPowAutoConfiguration {

    /** 主存储 Bean 名称(启动日志与降级解析都按它识别"实现主存储的那个 PowStorage")。 */
    static final String PRIMARY_STORAGE_BEAN = "redisPowStorage";

    /**
     * PoW 主存储:{@code @Primary} 让"用户另外声明了 PowStorage"时按类型注入仍有确定答案,
     * 否则签发器/校验器/指标都会因为二义而让应用起不来。
     *
     * <p>检测到 Redis 时用它替换默认的内存实现,外面套一层 {@link FailModePowStorage}:
     * Redis 是网络依赖,一次抖动不该让校验接口既不 fail-closed 也不 fail-open,而是抛 500;
     * 故障时怎么取舍交给 {@code easy-pow.storage-fail-mode}。</p>
     *
     * <p>{@code @Primary} 只能有一个赢家:这里不要再注册任何别名 Bean,否则同类型会出现
     * "more than one 'primary' bean found"(Spring 6 把多 primary 视为无 primary)。</p>
     */
    // 返回类型必须写 PowStorage:条件、注入、指标都按 PowStorage 类型判定;
    // 写成包装器类型会让"容器里有没有存储"这件事在不同求值阶段得到不同答案。
    // 方法名不是 powStorage:自动配置类的方法名就是 Bean 名,而用户完全可能用
    // @Bean public PowStorage powStorage() 覆盖本 Bean;若同名,Spring 把两者当作
    // "同一 Bean 的定义覆盖",我们的定义会静默消失(条件已过、Bean 却不在)。
    @Bean
    @Primary
    public PowStorage redisPowStorage(StringRedisTemplate redisTemplate,
                                      PowProperties properties,
                                      ObjectProvider<PowStorage> storageProvider,
                                      org.springframework.beans.factory.ListableBeanFactory beanFactory) {
        RedisPowStorage redis = new RedisPowStorage(redisTemplate, java.time.Clock.systemUTC(),
                properties.getRedisKeyPrefix());
        PowStorageFailMode mode = properties.getStorageFailMode();
        PowStorage fallback = null;
        if (mode == PowStorageFailMode.OPEN) {
            fallback = resolveFallback(properties, storageProvider, beanFactory);
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
    private static PowStorage resolveFallback(PowProperties properties,
                                              ObjectProvider<PowStorage> storageProvider,
                                              org.springframework.beans.factory.ListableBeanFactory beanFactory) {
        String name = properties.getFallbackStorage();
        if (name != null && !name.isBlank()) {
            // 按容器里的真实 Bean 名称匹配。旧实现拿 getClass().getSimpleName() 去比,
            // 只要用户给 Bean 起了别名就永远匹配不上,然后静默落到下面的"第一个自定义存储",
            // 容器里有多个 PowStorage 时会取错且没有任何日志。
            for (String beanName : beanFactory.getBeanNamesForType(PowStorage.class)) {
                // 跳过宿主 Bean:OPEN 模式且没有任何用户存储时,唯一候选就是本 Bean 自己,
                // 直接 getBean 会撞上 "Requested bean is currently in creation" 让应用起不来。
                if (name.equals(beanName) && !beanName.equals(PRIMARY_STORAGE_BEAN)) {
                    return beanFactory.getBean(beanName, PowStorage.class);
                }
            }
            LOG.warn("easy-pow.fallback-storage 指定的 Bean [{}] 不是容器里的 PowStorage,回退到自动选择",
                    name);
        }
        // 只排除本 Bean 的**名字**:按名字过滤是惰性的,不会去实例化候选;
        // 一旦调用 orderedStream() 就会实例化正在创建的自己,OPEN 模式无用户存储时必然
        // "Requested bean is currently in creation"。也不按实现类过滤 —— 用户完全可能
        // 拿自己的 InMemoryPowStorage 或包装类做降级存储,按类排除会误伤。
        java.util.List<PowStorage> candidates = java.util.Arrays.stream(
                        beanFactory.getBeanNamesForType(PowStorage.class))
                .filter(beanName -> !beanName.equals(PRIMARY_STORAGE_BEAN))
                .map(beanName -> beanFactory.getBean(beanName, PowStorage.class))
                .toList();
        return candidates.stream()
                .findFirst()
                .orElseGet(() -> new InMemoryPowStorage(properties.getMaxPendingChallenges()));
    }

    /** 降级存储按名称匹配失败时只能自动选择,此时必须留下 WARN,否则配置写错完全无声。 */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RedisPowAutoConfiguration.class);
}
