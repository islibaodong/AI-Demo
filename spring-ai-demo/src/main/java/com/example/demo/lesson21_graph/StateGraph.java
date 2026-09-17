package com.example.demo.lesson21_graph;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第 21 课：mini 状态图引擎 —— Java 版 LangGraph 的核心思想，手写讲透。
 *
 * <p>lesson16 的 Agent 是<b>模型驱动</b>的循环（模型看工具清单决定下一步，框架黑盒执行，
 * 无步数上限）；本课是<b>业务驱动</b>的编排：把流程画成图——节点是函数，边是流转规则，
 * 每一步都由业务代码显式控制。两者不是替代关系：模型自主选工具用 Agent，
 * 流程必须可控可审计（审批、多分支、固定环节）用图。</p>
 *
 * <p><b>与 LangGraph 对照</b>（学过 Python 侧的话一目了然）：</p>
 * <ul>
 *   <li>{@link Node} ≈ {@code StateGraph.add_node(name, fn)}——节点收状态、返回新状态；</li>
 *   <li>{@link Router} ≈ {@code add_conditional_edges}——按状态路由到下一个节点；</li>
 *   <li>{@link HumanInputRequired} ≈ {@code interrupt()}——节点抛它即挂起等待人工输入；</li>
 *   <li>checkpoint + resume（见 {@link CompiledGraph}）≈ {@code checkpointer} +
 *       {@code Command(resume=...)}</li>
 * </ul>
 *
 * <p>刻意保持极简：状态就是 {@code Map<String, Object>}（LangGraph 是带 channel 的强类型
 * state，思想相同）；没有并行分支、没有子图——那些是生产引擎（LangGraph/Flowable）的事，
 * 原理掌握后换引擎只是 API 问题。</p>
 */
public class StateGraph {

    /** 终点哨兵：Router 返回它（或没有出边）即结束 */
    public static final String END = "__end__";

    /** 节点：吃状态、吐状态。纯函数，不持有跨请求状态 */
    @FunctionalInterface
    public interface Node {
        Map<String, Object> apply(Map<String, Object> state);
    }

    /** 条件路由：看状态决定下一个节点名（返回 {@link #END} 结束） */
    @FunctionalInterface
    public interface Router {
        String route(Map<String, Object> state);
    }

    /**
     * 人在环中的挂起信号：节点抛出它，引擎立刻冻结当前状态与执行位置（见
     * {@link CompiledGraph} 的 checkpoint），等人工输入后从<b>同一个节点</b>重入——
     * 节点第二次执行时从状态里读到人工决定，走不同分支。
     */
    public static class HumanInputRequired extends RuntimeException {
        public HumanInputRequired(String message) {
            super(message);
        }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    /** 固定出边：from → to */
    private final Map<String, String> edges = new HashMap<>();
    /** 条件出边：from → (看状态定 to) */
    private final Map<String, Router> conditionalEdges = new HashMap<>();
    private String entry;

    public StateGraph addNode(String name, Node node) {
        nodes.put(name, node);
        return this;
    }

    /** 固定边：执行完 from 直接到 to */
    public StateGraph addEdge(String from, String to) {
        edges.put(from, to);
        return this;
    }

    /** 条件边：执行完 from 由 router 决定去哪 */
    public StateGraph addConditionalEdge(String from, Router router) {
        conditionalEdges.put(from, router);
        return this;
    }

    public StateGraph entry(String name) {
        this.entry = name;
        return this;
    }

    public CompiledGraph compile(CheckpointStore checkpoints) {
        return new CompiledGraph(this, checkpoints);
    }

    /** 自定义步数上限的编译（默认 24；循环多的图可放宽，但必须有上限） */
    public CompiledGraph compile(CheckpointStore checkpoints, int maxSteps) {
        return new CompiledGraph(this, checkpoints, maxSteps);
    }

    // ---- 包级访问器（CompiledGraph 同包使用） ----

    Map<String, Node> nodes() {
        return nodes;
    }

    String nextOf(String node, Map<String, Object> state) {
        Router router = conditionalEdges.get(node);
        if (router != null) {
            String next = router.route(state);
            return next == null ? END : next;
        }
        return edges.getOrDefault(node, END);
    }

    String entry() {
        return entry;
    }
}
