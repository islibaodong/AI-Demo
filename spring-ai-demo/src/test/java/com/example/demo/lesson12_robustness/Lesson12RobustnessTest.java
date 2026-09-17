package com.example.demo.lesson12_robustness;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 12 课单元测试：不调用任何模型。验证手写熔断器的状态机、
 * 降级模板的行为，以及 application.yml 里健壮性配置项真的绑定生效。
 */
@SpringBootTest
class Lesson12RobustnessTest {

    @Autowired
    private Environment env;

    // ---------- 熔断器状态机 ----------

    @Test
    void breakerStaysClosedWhileSucceeding() {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(2, Duration.ofSeconds(60));
        assertThat(breaker.call(() -> "ok")).isEqualTo("ok");
        assertThat(breaker.call(() -> "ok")).isEqualTo("ok");
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
    }

    @Test
    void breakerOpensAfterConsecutiveFailuresAndFailsFast() {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(2, Duration.ofSeconds(60));
        AtomicInteger networkCalls = new AtomicInteger();

        // 连续失败 2 次 → OPEN（异常原样上抛，熔断器不吞）
        assertThatThrownBy(() -> breaker.call(() -> { networkCalls.incrementAndGet(); throw new IllegalStateException("模型 500"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> breaker.call(() -> { networkCalls.incrementAndGet(); throw new IllegalStateException("模型 500"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);

        // OPEN 期间：快速失败，不再发起网络调用（调用数停在 2）
        assertThatThrownBy(() -> breaker.call(() -> { networkCalls.incrementAndGet(); return "ok"; }))
                .isInstanceOf(SimpleCircuitBreaker.CircuitOpenException.class)
                .hasMessageContaining("快速失败");
        assertThat(networkCalls.get()).isEqualTo(2);
    }

    @Test
    void breakerHalfOpensAfterQuietPeriodAndClosesOnSuccess() throws Exception {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(1, Duration.ofMillis(50));

        assertThatThrownBy(() -> breaker.call(() -> { throw new IllegalStateException("挂了"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);

        Thread.sleep(80);   // 静默期（50ms）已过

        // 放行的这次请求就是"试探"：成功则恢复 CLOSED
        assertThat(breaker.call(() -> "恢复")).isEqualTo("恢复");
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
    }

    @Test
    void breakerHalfOpenTrialFailureReopens() throws Exception {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(1, Duration.ofMillis(50));

        assertThatThrownBy(() -> breaker.call(() -> { throw new IllegalStateException("挂了"); })).isInstanceOf(RuntimeException.class);
        Thread.sleep(80);

        // HALF_OPEN 试探失败 → 立刻回到 OPEN
        assertThatThrownBy(() -> breaker.call(() -> { throw new IllegalStateException("还在挂"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
    }

    // ---------- 降级模板 ----------

    @Test
    void fallbackUsesBackupWhenPrimaryFails() {
        String result = Lesson12Controller.withFallback(
                () -> { throw new IllegalStateException("主模型超时"); },
                e -> "降级回答（原因: " + e.getMessage() + "）");
        assertThat(result).isEqualTo("降级回答（原因: 主模型超时）");
    }

    @Test
    void fallbackPassesThroughWhenPrimarySucceeds() {
        String result = Lesson12Controller.withFallback(
                () -> "主模型回答",
                e -> "不应该走到这里");
        assertThat(result).isEqualTo("主模型回答");
    }

    // ---------- 配置绑定 ----------

    @Test
    void robustnessPropertiesBoundFromYaml() {
        // lesson12 显式配置的 SDK 超时与重试次数（2.0 的配置入口是 spring.ai.openai.*）
        assertThat(env.getProperty("spring.ai.openai.timeout")).isEqualTo("60s");
        assertThat(env.getProperty("spring.ai.openai.max-retries")).isEqualTo("2");
    }
}
