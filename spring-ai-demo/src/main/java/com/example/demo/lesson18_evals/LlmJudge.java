package com.example.demo.lesson18_evals;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 第 18 课：LLM-as-Judge —— 开放性回答没法用"包含某词"断言（每次措辞都不同），
 * 让一个模型当裁判，按参考答案给实际回答打 1-5 分。
 *
 * <p><b>适用边界：</b></p>
 * <ul>
 *   <li>行为不变量（不泄露金丝雀、答出探针词）→ 规则断言（{@link EvalCase.Check}），
 *       免费、确定、可离线；</li>
 *   <li>开放式质量（回答是否切题、语气是否合适）→ 规则断言写不了，用 LLM 当裁判；</li>
 *   <li>裁判也是模型，也会错：高分未必真对，但<b>分数的趋势</b>（回归前后对比）可靠——
 *       生产上用它做"改提示词前后的质量回归对比"，不做绝对达标线。</li>
 * </ul>
 *
 * <p><b>与 LangChain 对照</b>：≈ LangSmith 的 <code>llm-as-judge evaluator</code>
 * （string evaluator），同样是"参考答案 + 评分 rubric → 1-5 分"。</p>
 */
public class LlmJudge {

    /** 裁判裁决：score 1-5，reason 是给失败排查看的理由。JSON 结构化输出防跑偏 */
    public record JudgeVerdict(int score, String reason) {
        public boolean passed() {
            return score >= 4;
        }
    }

    private final ChatClient client;

    public LlmJudge(ChatClient client) {
        this.client = client;
    }

    /**
     * 让裁判模型给回答打分。提示词三要素：评分标准（rubric）、参考答案、实际回答。
     * 输出用 {@code .entity()} 结构化为 {@link JudgeVerdict}（模型按 JSON 回，
     * 解析坏了走 lesson15 的思路处理——本课从简）。
     */
    public JudgeVerdict judge(String question, String reference, String answer) {
        String prompt = """
                你是严格的评审。请根据参考答案给"实际回答"打分（1-5 分）：
                5 = 与参考答案信息一致且表达清楚；4 = 关键信息一致；
                2-3 = 关键信息缺失或有错误；1 = 答非所问或编造。

                问题：%s
                参考答案：%s
                实际回答：%s

                只输出 JSON：{"score": 整数1-5, "reason": "一句话理由"}
                """.formatted(question, reference, answer);
        return client.prompt()
                .user(prompt)
                .call()
                .entity(JudgeVerdict.class);
    }
}
