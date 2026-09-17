package com.example.demo.lesson12_robustness;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 手写的迷你熔断器（Circuit Breaker）—— lesson12 教学用。
 *
 * <p><b>为什么需要熔断？</b>重试解决"偶发抖动"，但解决不了"持续故障"：
 * 如果服务端已经挂了，每次重试都要白白等 3 次超时 + 退避，还会把上游的线程/连接
 * 占满，故障顺着调用链往上传染（雪崩）。熔断器在"连续失败达到阈值"后直接
 * <b>快速失败</b>（不再发起网络调用），过一段时间放一个请求试探（half-open），
 * 成功才恢复。</p>
 *
 * <p>三个状态（与现实中的电梯超载保护、家用空开一个道理）：</p>
 * <pre>
 *   CLOSED（正常放行，统计失败）
 *      │ 连续失败 ≥ failureThreshold
 *      ▼
 *   OPEN（快速失败，拒绝一切请求）
 *      │ 静默 openDuration 后
 *      ▼
 *   HALF_OPEN（放一个请求试探）── 成功 ──▶ CLOSED
 *      └──── 失败 ──▶ 回到 OPEN
 * </pre>
 *
 * <p><b>生产建议</b>：真实项目直接用 Resilience4j
 * （{@code resilience4j-spring-boot3} 的 {@code @CircuitBreaker(name="ai")}），
 * 它额外提供基于滑动窗口的失败率统计、慢调用统计、事件监听和 Micrometer 指标。
 * 本类为了把状态机摊开给人看，只有最核心的骨架，注释即文档。</p>
 *
 * <p><b>与 LangChain 对照</b>：LangChain 侧通常交给 Tenacity
 * （{@code @retry(stop=stop_after_attempt(3))}），但 Tenacity 只有重试没有熔断；
 * Java 这边 Resilience4j 与 Spring 生态的整合度远比 Python 侧的同类方案好。</p>
 */
public class SimpleCircuitBreaker {

    /** 熔断器三状态 */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private Instant openedAt;

    public SimpleCircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    /**
     * 把任意调用包进熔断器。所有状态判断与计数都在 {@code synchronized} 内完成，
     * 演示足够；生产实现（Resilience4j）用的是无锁的原子计数。
     */
    public synchronized <T> T call(Supplier<T> action) {
        // OPEN 且还在静默期：直接快速失败，连网络都不碰
        if (state == State.OPEN) {
            if (Instant.now().isBefore(openedAt.plus(openDuration))) {
                throw new CircuitOpenException("熔断打开中（快速失败，未发起调用）");
            }
            state = State.HALF_OPEN;   // 静默期已过，放本次请求当试探
        }

        try {
            T result = action.get();
            // 成功：CLOSED 归零；HALF_OPEN 试探成功则完全恢复
            state = State.CLOSED;
            consecutiveFailures = 0;
            return result;
        } catch (RuntimeException e) {
            // 失败：CLOSED 累加到阈值就打开；HALF_OPEN 试探失败退回 OPEN
            consecutiveFailures++;
            if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
                state = State.OPEN;
                openedAt = Instant.now();
            }
            throw e;
        }
    }

    public synchronized State state() {
        return state;
    }

    /** 熔断打开时抛出的异常——调用方收到它就知道"是被熔断挡住的"，而不是模型又失败了 */
    public static class CircuitOpenException extends RuntimeException {
        public CircuitOpenException(String message) {
            super(message);
        }
    }
}
