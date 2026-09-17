package com.example.demo.lesson18_evals;

import java.util.List;

/**
 * 第 18 课：评估与回归（Evals）—— 把"系统行为对不对"从肉眼验收变成可自动执行的一等公民。
 *
 * <p><b>为什么 LLM 应用尤其需要 Evals：</b>单元测试断言确定性输出，而 LLM 输出
 * 每次都不同——但"行为不变量"是确定的：创始人探针必须答出编造的名字（RAG 生效）、
 * 注入句绝不能泄露金丝雀（防护生效）。把这些不变量固化成<b>评估用例集</b>，
 * 每次改提示词/换模型/升级框架后批量重跑，就是 LLM 应用的回归测试。</p>
 *
 * <p>{@link EvalCase} = 一条探针：输入 + 若干断言（{@link Check}）。断言刻意保持
 * 简单（包含/不包含/非空）——评估的第一版就该这样，先跑起来再谈复杂度。</p>
 *
 * <p><b>与 LangChain 对照</b>：EvalCase ≈ <code>LangSmith</code> 的 dataset example +
 * evaluator；跑批 ≈ <code>pytest + langsmith.evaluate()</code>。理念相同：
 * 用例集（数据）与执行器（代码）分离。</p>
 */
public record EvalCase(String name, String tag, String input, List<Check> checks) {

    /** 一条断言：对系统输出做一次判断 + 可读的描述（失败时能直接看懂哪里不对） */
    public interface Check {
        boolean passes(String output);

        String describe();
    }

    /** 输出必须包含某段文本（探针答案、关键信息） */
    public record Contains(String needle) implements Check {
        @Override public boolean passes(String output) {
            return output != null && output.contains(needle);
        }
        @Override public String describe() { return "输出应包含「" + needle + "」"; }
    }

    /** 输出必须<b>不</b>包含某段文本（金丝雀泄露扫描——安全探针的标配） */
    public record NotContains(String needle) implements Check {
        @Override public boolean passes(String output) {
            return output == null || !output.contains(needle);
        }
        @Override public String describe() { return "输出不得包含「" + needle + "」"; }
    }

    /** 输出非空白（冒烟探针：链路通、有产出即可，不卡内容） */
    public record NonBlank() implements Check {
        @Override public boolean passes(String output) {
            return output != null && !output.isBlank();
        }
        @Override public String describe() { return "输出应为非空白"; }
    }

    public static EvalCase of(String name, String tag, String input, Check... checks) {
        return new EvalCase(name, tag, input, List.of(checks));
    }
}
