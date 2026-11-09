package io.github.lucky845.basic.pow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 存储实现的自动选择逻辑,三条路径:
 * 无 Redis → 内存;有 {@link StringRedisTemplate} → Redis;用户自定义 Bean → 用户优先。
 *
 * <p>未连真实 Redis,行为经 mock 佐证。
 *
 * @author created by lucky845
 */
@DisplayName("存储自动选择")
class PowStorageAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // PowProperties.secret 必填,自动配置创建 Issuer/Verifier 时会快速失败
            .withPropertyValues("easy-pow.secret=test-secret")
            .withConfiguration(AutoConfigurations.of(
                    RedisPowAutoConfiguration.class,
                    PowAutoConfiguration.class));

    @Test
    @DisplayName("没有 StringRedisTemplate 时使用内存存储")
    void fallsBackToMemoryWithoutRedis() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PowStorage.class);
            assertThat(context.getBean(PowStorage.class))
                    .isInstanceOf(InMemoryPowStorage.class);
        });
    }

    @Test
    @DisplayName("存在 StringRedisTemplate 时自动切换到 Redis 存储(外套故障策略装饰器)")
    void switchesToRedisWhenTemplatePresent() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(PowStorage.class);
                    assertThat(context.getBean(PowStorage.class))
                            .isInstanceOf(FailModePowStorage.class);
                    assertThat(context).hasSingleBean(FailModePowStorage.class);
                });
    }

    @Test
    @DisplayName("用户自定义 PowStorage 优先级最高,不被内置实现覆盖")
    void customStorageWins() {
        InMemoryPowStorage custom = new InMemoryPowStorage();
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean("myStorage", PowStorage.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(PowStorage.class);
                    assertThat(context.getBean(PowStorage.class)).isSameAs(custom);
                });
    }

    @Test
    @DisplayName("fail-mode=OPEN 时按真实 Bean 名称挑降级存储,多候选也不会取错")
    void fallbackStorageMatchesRealBeanName() {
        InMemoryPowStorage first = new InMemoryPowStorage();
        InMemoryPowStorage wanted = new InMemoryPowStorage();
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                // 故意让"想要的"排在后面:旧实现拿 getSimpleName() 比对,永远匹配不上,
                // 会静默取到 first。
                .withBean("aStorage", PowStorage.class, () -> first)
                .withBean("wantedStorage", PowStorage.class, () -> wanted)
                .withPropertyValues("easy-pow.secret=s", "easy-pow.storage-fail-mode=OPEN",
                        "easy-pow.fallback-storage=wantedStorage")
                .run(context -> {
                    // 自动配置的宿主 Bean 返回类型是 PowStorage,不按实现类暴露:
                    // 按 FailModePowStorage 类型取 Bean 会取不到(也与 PowMetrics 的
                    // ObjectProvider<FailModePowStorage> 容忍逻辑保持一致)。
                    // 用户只要声明了自己的 PowStorage,本配置让路 —— 这里断言的就是"让路"本身。
                    assertThat(context).doesNotHaveBean("redisPowStorage");
                    assertThat(context.getBeanNamesForType(PowStorage.class))
                            .containsExactlyInAnyOrder("aStorage", "wantedStorage");
                });
    }

    @Test
    @DisplayName("max-pending-challenges 传入内存存储,超限拒绝签发")
    void pendingLimitIsPassedToMemoryStorage() {
        runner.withPropertyValues("easy-pow.max-pending-challenges=1")
                .run(context -> {
                    PowStorage storage = context.getBean(PowStorage.class);
                    long expiresAt = System.currentTimeMillis() + 60_000;
                    storage.save("k1", expiresAt, 1000);
                    assertThrows(IllegalStateException.class,
                            () -> storage.save("k2", expiresAt, 1000));
                });
    }
}
