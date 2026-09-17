package com.example.demo.lesson15_structured_output;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.springframework.ai.converter.BeanOutputConverter;

import tools.jackson.core.JacksonException;

/**
 * 第 15 课：结构化输出的<b>解析修复管道</b>（语义修复模式）。
 *
 * <p><b>为什么结构化输出会失败？</b>模型是"文本生成器"不是"JSON 编译器"，
 * 即便提示词里给了 JSON Schema（{@code .entity()} 的原理），输出仍可能坏在四种地方：
 * </p>
 * <ul>
 *   <li><b>寒暄包裹</b>（最常见）：「好的，这是您要的 JSON：{...} 希望有帮助！」——
 *       模型的"礼貌"直接让解析器崩掉；</li>
 *   <li><b>截断</b>：maxTokens 设小了或输出超长，JSON 只剩半截；</li>
 *   <li><b>markdown 代码块包裹</b>：1.x 需要手动剥围栏，<b>2.0 已内置
 *       MarkdownCodeBlockCleaner 自动处理</b>（实测验证），不再是问题；</li>
 *   <li><b>字段类型错</b>：把 amount 写成 "一百二十九"，反序列化抛
 *       InvalidFormatException。</li>
 * </ul>
 *
 * <p><b>修复管道的三级策略</b>（成本从低到高，逐级升级）：</p>
 * <ol>
 *   <li><b>DIRECT</b>：直接解析——干净输出一步到位，零额外成本；</li>
 *   <li><b>EXTRACT</b>：本地抽取——截取第一个 '{' 到最后一个 '}' 再解析，
 *       专治寒暄包裹（纯字符串操作，不调模型、零成本）；</li>
 *   <li><b>MODEL_REPAIR</b>：模型自修复——把坏输出 + 解析错误喂回模型，
 *       让它重出一份合法 JSON（多花一次调用的钱，但能救回截断/类型错这类
 *       本地无能为力的场景）。</li>
 * </ol>
 *
 * <p>全部失败则返回 {@link Parsed#FAILED} 并带上错误摘要，由调用方决定降级
 * （默认值/人工审核/抛异常），<b>修复管道本身绝不抛异常</b>——把"输出坏了"
 * 从 500 错误变成一个可观测、可降级的普通分支。</p>
 *
 * <p><b>与 LangChain 对照</b>：LangChain 的 OutputFixingParser 是同款思路——
 * 坏输出 + 原异常喂给另一个 LLM 修复；本类把它做成了带分级策略的版本。
 * （Pydantic 的 ValidationError 修复、Instructor 库的重试机制也是这一族。）</p>
 */
public class StructuredOutputRepairer {

    /** 修复实际用了哪一级策略（可观测：统计各级命中率，EXTRACT 命中多说明提示词该改了） */
    public enum Strategy { DIRECT, EXTRACT, MODEL_REPAIR, FAILED }

    /**
     * 解析结果。value 仅在 strategy != FAILED 时非空；
     * attempts 是实际尝试次数（可观测指标：正常应 ≤1，持续 >1 说明上游提示词有问题）；
     * errorSummary 是最后一级失败的错误（FAILED 时用于告警/日志）。
     */
    public record Parsed<T>(T value, Strategy strategy, int attempts, String errorSummary) {

        public static <T> Parsed<T> failed(String errorSummary, int attempts) {
            return new Parsed<>(null, Strategy.FAILED, attempts, errorSummary);
        }
    }

    // ---------- 一级：直接解析 ----------

    /** 直接解析（供测试与外部复用）。失败抛 JacksonException（unchecked） */
    public <T> T directParse(String raw, Class<T> type) {
        return new BeanOutputConverter<>(type).convert(raw);
    }

    // ---------- 二级：本地抽取 ----------

    /**
     * 截取第一个 '{' 到最后一个 '}'——去掉前后寒暄。
     * 专治「好的，这是您要的 JSON：{...} 希望有帮助！」这类"礼貌性失败"。
     * 注意：对<b>截断</b>的 JSON 无能为力（花括号不配对），那种只能走模型修复。
     */
    public String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    // ---------- 三级：模型自修复 ----------

    /** 构造修复提示词：坏输出 + 具体解析错误一起给模型——错误信息越具体，修得越准 */
    public String repairPrompt(String raw, String parseError) {
        return ("上一次回复试图输出 JSON，但解析失败（错误：%s）。\n"
                + "请修复下面这段内容，只输出修复后的合法 JSON，不要任何解释、寒暄或 markdown 代码块：\n%s")
                .formatted(parseError, raw);
    }

    /**
     * 完整管道：DIRECT → EXTRACT → MODEL_REPAIR 逐级升级。
     *
     * @param raw        模型的原始输出
     * @param type       目标类型
     * @param modelRepair 模型修复函数（输入修复提示词，返回模型的新输出）；传 null 表示不允许调模型
     */
    public <T> Parsed<T> parse(String raw, Class<T> type, UnaryOperator<String> modelRepair) {
        List<String> errors = new ArrayList<>();

        // 一级：直接解析
        try {
            return new Parsed<>(directParse(raw, type), Strategy.DIRECT, 1, null);
        } catch (JacksonException e) {
            errors.add(e.getMessage());
        }

        // 二级：本地抽取（寒暄包裹的克星）
        String extracted = extractJson(raw);
        if (extracted != null) {
            try {
                return new Parsed<>(directParse(extracted, type), Strategy.EXTRACT, 2, null);
            } catch (JacksonException e) {
                errors.add(e.getMessage());
            }
        }

        // 三级：模型自修复（截断/类型错这类本地救不了的）
        if (modelRepair != null) {
            try {
                String repaired = modelRepair.apply(repairPrompt(raw, lastError(errors)));
                return new Parsed<>(directParse(repaired, type), Strategy.MODEL_REPAIR, 3, null);
            } catch (JacksonException e) {
                errors.add(e.getMessage());
            }
        }

        return Parsed.failed(lastError(errors), errors.size() + 1);
    }

    private static String lastError(List<String> errors) {
        return errors.isEmpty() ? "unknown" : errors.get(errors.size() - 1);
    }
}
