package com.example.demo.lesson18_evals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 第 18 课：评估执行器 —— 把用例集批量灌进"被测系统"，汇总通过率与失败明细。
 *
 * <p><b>被测系统就是一个 {@code String -> String} 的函数</b>——这是本课最重要的
 * 抽象：不管你的应用内部是 ChatClient + Advisor、RAG 管道还是 Agent 编排，
 * 对评估器来说都只是一个"吃问题、吐回答"的黑盒。评估代码因此与应用解耦，
 * 被测入口怎么重构都不影响用例集。</p>
 *
 * <p>两条执行原则（生产 Evals 的共识）：</p>
 * <ul>
 *   <li><b>单条失败不中断</b>：回归跑批要一次拿到全部失败明细，而不是 fail-fast；</li>
 *   <li><b>被测系统抛异常 = 该条失败</b>：异常本身（500）也是要捕捉的回归信号，
 *       记录进结果而不是让整个跑批崩掉。</li>
 * </ul>
 *
 * <p><b>与 LangChain 对照</b>：≈ <code>langsmith.evaluate(dataset, llm_or_chain, evaluators)</code>。</p>
 */
public final class EvalRunner {

    /** 单条用例的结果：通过与否 + 失败断言的可读明细 */
    public record CaseResult(String name, String tag, boolean passed, List<String> failures) {
    }

    /** 整个跑批的汇总：通过率 + 逐条明细（CI 里 passRate < 1 即退出非零） */
    public record Summary(int total, int passed, List<CaseResult> results) {
        public double passRate() {
            return total == 0 ? 1.0 : (double) passed / total;
        }
    }

    /**
     * 批量执行：每条用例调一次被测系统，逐条断言，全部跑完才返回。
     */
    public Summary run(List<EvalCase> cases, UnaryOperator<String> systemUnderTest) {
        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            results.add(runOne(c, systemUnderTest));
        }
        long passed = results.stream().filter(CaseResult::passed).count();
        return new Summary(results.size(), (int) passed, List.copyOf(results));
    }

    private CaseResult runOne(EvalCase c, UnaryOperator<String> sut) {
        String output;
        try {
            output = sut.apply(c.input());
        }
        catch (Exception e) {
            // 异常也是评估结果：500 与"答错了"一样是回归信号，记下来继续跑下一条
            return new CaseResult(c.name(), c.tag(), false,
                    List.of(("被测系统抛出异常：" + e.getClass().getSimpleName()
                            + "（" + e.getMessage() + "）")));
        }
        List<String> failures = c.checks().stream()
                .filter(check -> !check.passes(output))
                .map(check -> check.describe() + (output == null ? "" :
                        "，实际输出片段：「" + snippet(output) + "」"))
                .toList();
        return new CaseResult(c.name(), c.tag(), failures.isEmpty(), failures);
    }

    /** 失败明细里带输出片段（截 60 字），排查时不用翻日志 */
    private static String snippet(String output) {
        String oneLine = output.replaceAll("\\s+", " ");
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }
}
