package com.example.demo.lesson21_graph;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 21 课：检查点仓库 —— 人在环中能"挂起再恢复"的关键。
 *
 * <p>节点抛 {@link StateGraph.HumanInputRequired} 时，引擎把「执行到哪了 + 当前状态 +
 * 已走过的路径」整个冻结成 {@link Snapshot} 存进来；人工审批是<b>另一个 HTTP 请求</b>
 * （可能几分钟后、甚至换个人来点），resume 时凭 executionId 取回快照，从挂起节点重入。</p>
 *
 * <p>这里是内存实现（进程重启即丢）；生产对应 LangGraph 的 checkpointer
 * （{@code SqliteSaver/PostgresSaver}）或自建的状态表——本质都是
 * 「执行位置 + 状态」的持久化， durable execution 的核心思想。</p>
 */
public class CheckpointStore {

    /** 一次挂起的执行快照：恢复所需的全部信息 */
    public record Snapshot(
            String executionId,
            /** 挂在哪个节点（resume 时从这里重入） */
            String currentNode,
            /** 挂起时的完整状态（人工输入会合并进来） */
            Map<String, Object> state,
            /** 挂起前走过的节点路径 */
            List<String> trace,
            /** 已执行步数（resume 后继续计数，全局循环上限不因挂起重置） */
            int steps,
            /** 等待什么人工输入（展示给审批人看） */
            String pendingPrompt) {
    }

    private final Map<String, Snapshot> store = new ConcurrentHashMap<>();

    public void save(Snapshot snapshot) {
        store.put(snapshot.executionId(), snapshot);
    }

    /** 取走快照（取即删）：一次审批只消费一次，重复 resume 会拿不到而报错 */
    public Snapshot take(String executionId) {
        return store.remove(executionId);
    }

    /** 查看快照（不取走）：给 pending 列表用 */
    public Snapshot peek(String executionId) {
        return store.get(executionId);
    }

    public List<Snapshot> pending() {
        return List.copyOf(store.values());
    }
}
