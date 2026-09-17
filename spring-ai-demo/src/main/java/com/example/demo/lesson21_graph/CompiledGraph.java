package com.example.demo.lesson21_graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 第 21 课：编译后的可执行图 —— 引擎循环 + 挂起/恢复 + 循环保护。
 *
 * <p>主循环只有四行的事：取节点 → 执行 → 路由 → 直到 END。三个生产级保障：</p>
 * <ul>
 *   <li><b>步数上限</b>（{@code maxSteps}）：图可以有环（如"修改→再审"循环），
 *       但必须有硬上限兜底——这是 lesson16 框架内置循环最被诟病的缺失
 *       （无步数上限），图编排的第一天就该补上；</li>
 *   <li><b>挂起</b>：节点抛 {@link StateGraph.HumanInputRequired} 时，
 *       把执行位置+状态+路径冻结进 {@link CheckpointStore}，返回 suspended 执行句柄；</li>
 *   <li><b>恢复</b>：{@link #resume(String, Map)} 凭 executionId 取回快照，
 *       把人工输入合并进状态后<b>从挂起节点重入</b>——节点第二次执行时读状态分支。</li>
 * </ul>
 *
 * <p><b>与 LangChain 对照</b>：≈ LangGraph 的 {@code graph.invoke / Command(resume=...)}；
 * trace 就是 LangGraph 的 step 轨迹（LangSmith 里画出来的那条执行路径）。</p>
 */
public class CompiledGraph {

    /** 一次执行的对外句柄：done 带终态；suspended 带 executionId 等审批 */
    public record Execution(
            String status,
            String executionId,
            Map<String, Object> state,
            List<String> trace,
            String pendingPrompt) {

        public static final String DONE = "done";
        public static final String SUSPENDED = "suspended";
    }

    private final StateGraph spec;
    private final CheckpointStore checkpoints;
    private final int maxSteps;

    CompiledGraph(StateGraph spec, CheckpointStore checkpoints) {
        this(spec, checkpoints, 24);
    }

    CompiledGraph(StateGraph spec, CheckpointStore checkpoints, int maxSteps) {
        this.spec = spec;
        this.checkpoints = checkpoints;
        this.maxSteps = maxSteps;
    }

    /** 从入口跑一整张图；遇人工挂起信号则冻结并返回 suspended */
    public Execution run(Map<String, Object> initialState) {
        String executionId = UUID.randomUUID().toString().substring(0, 8);
        return drive(executionId, spec.entry(), initialState, new ArrayList<>(), 0);
    }

    /** 恢复挂起的执行：人工输入合并进状态，从挂起节点重入 */
    public Execution resume(String executionId, Map<String, Object> humanInput) {
        CheckpointStore.Snapshot snapshot = checkpoints.take(executionId);
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "执行 %s 不存在或已恢复（每个挂起只接受一次人工输入）".formatted(executionId));
        }
        if (humanInput != null) {
            snapshot.state().putAll(humanInput);
        }
        return drive(executionId, snapshot.currentNode(), snapshot.state(),
                new ArrayList<>(snapshot.trace()), snapshot.steps());
    }

    /** 挂起中的执行列表（给审批待办页用） */
    public List<CheckpointStore.Snapshot> pending() {
        return checkpoints.pending();
    }

    public CheckpointStore.Snapshot pendingOf(String executionId) {
        return checkpoints.peek(executionId);
    }

    // ---------- 引擎主循环（run 与 resume 共用） ----------

    private Execution drive(String executionId, String current,
            Map<String, Object> state, List<String> trace, int steps) {

        while (!StateGraph.END.equals(current)) {
            if (++steps > maxSteps) {
                throw new IllegalStateException(
                        ("工作流步数超过上限 %d（trace=%s）。图中存在无出口的循环，"
                                + "路由条件永远到不了 END——这是图编排最常见的写错方式。")
                                .formatted(maxSteps, trace));
            }
            trace.add(current);
            StateGraph.Node node = spec.nodes().get(current);
            if (node == null) {
                throw new IllegalStateException("节点 %s 未注册".formatted(current));
            }

            try {
                state = node.apply(state);
            } catch (StateGraph.HumanInputRequired e) {
                // 挂起：冻结「执行位置 + 状态 + 路径 + 步数」，等人工输入来 resume
                checkpoints.save(new CheckpointStore.Snapshot(
                        executionId, current, state, List.copyOf(trace), steps, e.getMessage()));
                return new Execution(Execution.SUSPENDED, executionId, state,
                        List.copyOf(trace), e.getMessage());
            }

            current = spec.nextOf(current, state);
        }
        return new Execution(Execution.DONE, executionId, state, List.copyOf(trace), null);
    }
}
