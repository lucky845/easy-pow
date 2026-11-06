package io.github.lucky845.basic.pow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
