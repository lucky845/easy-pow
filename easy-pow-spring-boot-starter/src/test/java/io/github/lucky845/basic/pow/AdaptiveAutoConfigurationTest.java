package io.github.lucky845.basic.pow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 难度自适应自动装配:默认关闭(静态难度);开启后签发器难度跟随控制器动态变化。
 *
 * @author created by lucky845
 */
@DisplayName("难度自适应自动装配")
class AdaptiveAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("easy-pow.secret=test-secret", "easy-pow.maxnumber=2000")
            .withConfiguration(AutoConfigurations.of(
                    RedisPowAutoConfiguration.class,
                    PowAutoConfiguration.class));

    @Test
    @DisplayName("默认关闭:不创建控制器,难度恒为配置值")
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(AdaptiveDifficulty.class);
            assertThat(context.getBean(PowChallengeIssuer.class).issue().maxnumber()).isEqualTo(2000);
        });
    }

    @Test
    @DisplayName("开启后:签发难度随观测动态调整")
    void enabledWiresIssuerAndVerifier() {
        runner.withPropertyValues("easy-pow.adaptive.enabled=true",
                        "easy-pow.adaptive.target-solve-ms=500",
                        "easy-pow.adaptive.min-maxnumber=1000",
                        "easy-pow.adaptive.max-maxnumber=5000")
                .run(context -> {
                    assertThat(context).hasSingleBean(AdaptiveDifficulty.class);
                    AdaptiveDifficulty adjuster = context.getBean(AdaptiveDifficulty.class);
                    PowChallengeIssuer issuer = context.getBean(PowChallengeIssuer.class);
                    // 尚无观测 → 基准难度
                    assertThat(issuer.issue().maxnumber()).isEqualTo(2000);
                    // 注入一次观测:实际耗时 400ms < 目标 500ms → 难度上调 2000 × 500/400 = 2500
                    adjuster.observeSolve(400);
                    assertThat(issuer.issue().maxnumber()).isEqualTo(2500);
                });
    }

    @Test
    @DisplayName("用户自定义 AdaptiveDifficulty Bean 优先")
    void customAdjusterWins() {
        runner.withPropertyValues("easy-pow.adaptive.enabled=true")
                .withBean(AdaptiveDifficulty.class, () -> new AdaptiveDifficulty(2_000, 500, 1_000, 3_000, 120_000))
                .run(context -> {
                    assertThat(context.getBean(AdaptiveDifficulty.class).currentMaxnumber()).isEqualTo(2000);
                    assertThat(context.getBean(PowChallengeIssuer.class).issue().maxnumber()).isEqualTo(2000);
                });
    }

    @Test
    @DisplayName("关闭自适应时,自定义监听器仍然收到事件")
    void listenerStillFiresWhenAdaptiveDisabled() {
        java.util.concurrent.atomic.AtomicInteger issued = new java.util.concurrent.atomic.AtomicInteger();
        runner.withBean(PowDifficultyListener.class, () -> new PowDifficultyListener() {
                    @Override
                    public void onIssued(String challengeKey) {
                        issued.incrementAndGet();
                    }
                })
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AdaptiveDifficulty.class);
                    context.getBean(PowChallengeIssuer.class).issue();
                    assertThat(issued.get()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("监听听抛异常不影响签发")
    void listenerFailureDoesNotBreakIssuing() {
        runner.withBean(PowDifficultyListener.class, () -> new PowDifficultyListener() {
                    @Override
                    public void onIssued(String challengeKey) {
                        throw new IllegalStateException("boom");
                    }
                })
                .run(context -> assertThat(context.getBean(PowChallengeIssuer.class).issue()).isNotNull());
    }

    @Test
    @DisplayName("Micrometer 在场时注册指标 Bean")
    void registersMetricsWhenMicrometerPresent() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(PoWMetrics.class);
                    PoWMetrics metrics = context.getBean(PoWMetrics.class);
                    context.getBean(PowChallengeIssuer.class).issue();
                    assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                            .get(PoWMetrics.ISSUED).counter().count()).isEqualTo(1.0);
                    assertThat(metrics.currentMaxnumber()).isEqualTo(2000.0);
                    // 未开自适应:EWMA 用 -1 表示"不适用",不能当成 0ms
                    assertThat(metrics.currentSolveMsEwma()).isEqualTo(-1D);
                    assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                            .get(PoWMetrics.SOLVE_MS_EWMA).gauge().value()).isEqualTo(-1D);
                });
    }

    @Test
    @DisplayName("自适应开启后 EWMA 指标反映实际观测值")
    void exposesSolveEwmaUnderAdaptive() {
        runner.withPropertyValues("easy-pow.adaptive.enabled=true")
                .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .run(context -> {
                    AdaptiveDifficulty adaptive = context.getBean(AdaptiveDifficulty.class);
                    adaptive.observeSolve(320L);
                    assertThat(adaptive.ewmaSolveMs()).isEqualTo(320D);
                    PoWMetrics metrics = context.getBean(PoWMetrics.class);
                    assertThat(metrics.currentSolveMsEwma()).isEqualTo(320D);
                    assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                            .get(PoWMetrics.SOLVE_MS_EWMA).gauge().value()).isEqualTo(320D);
                    // 算力指标必须与耗时一起暴露:只有耗时无法判断难度该不该动
                    assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                            .get(PoWMetrics.HASHES_PER_MS).gauge().value())
                            .isEqualTo(adaptive.ewmaHashesPerMs());
                    assertThat(adaptive.ewmaHashesPerMs()).isGreaterThan(0D);
                });
    }

    @Test
    @DisplayName("未开自适应时算力指标同样用 -1 表示不适用")
    void hashesPerMsIsNotApplicableWithoutAdaptive() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context.getBean(PoWMetrics.class).currentHashesPerMs()).isEqualTo(-1D);
                    assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                            .get(PoWMetrics.HASHES_PER_MS).gauge().value()).isEqualTo(-1D);
                });
    }

    @Test
    @DisplayName("未引入 micrometer 时安静跳过,核心 Bean 正常可用")
    void skipsMetricsGracefullyWithoutMicrometer() {
        new ApplicationContextRunner()
                .withPropertyValues("easy-pow.secret=test-secret")
                .withConfiguration(AutoConfigurations.of(PowAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(io.micrometer.core.instrument.MeterRegistry.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PoWMetrics.class);
                    assertThat(context.getBean(PowChallengeIssuer.class).issue()).isNotNull();
                });
    }
}
