package com.example.demo.lesson02_prompt;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 2 课：提示词工程（Prompt Engineering）与结构化输出。
 *
 * <p>这一课解决三个实战高频问题：</p>
 * <ol>
 *   <li><b>PromptTemplate</b>：把提示词做成带占位符的模板，变量运行时填充，避免字符串拼接。</li>
 *   <li><b>Few-shot（少样本）</b>：给模型几个「输入→输出」例子，示范它该怎样回答。</li>
 *   <li><b>结构化输出</b>：通过 <code>.entity(Class)</code> 让模型乖乖返回 JSON，
 *       并自动解析成 Java 对象 —— 再也不用自己手撕文本。</li>
 * </ol>
 *
 * <p><b>与 LangChain 对照</b>：
 * PromptTemplate ≈ LangChain 的 <code>PromptTemplate</code>；few-shot ≈ 手动塞示例消息；
 * 结构化输出 ≈ <code>with_structured_output() / OutputParser</code>。</p>
 */
@RestController
public class Lesson02Controller {

    private final ChatClient chatClient;

    public Lesson02Controller(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 翻译接口：用 PromptTemplate 渲染一段带变量的用户提示。
     * GET /lesson2/translate?text=hello
     */
    @GetMapping("/lesson2/translate")
    public String translate(@RequestParam String text) {
        // 1) 定义模板字符串，{tone} 和 {text} 是占位符
        String template = "请用{tone}的语气，把“{text}”翻译成中文。只输出译文。";
        // 2) 用 PromptTemplate 渲染，此时 {text} 被真实值替换
        String renderedUser = new PromptTemplate(template)
                .render(Map.of("tone", "轻松友好", "text", text));

        return chatClient
                .prompt()
                .user(renderedUser)
                .call()
                .content();
    }

    /**
     * 结构化输出：要求模型返回 JSON，并自动解析成 {@link TranslationResult}。
     * GET /lesson2/structured?text=Spring AI rocks
     */
    @GetMapping("/lesson2/structured")
    public TranslationResult structured(@RequestParam(defaultValue = "Spring AI rocks") String text) {

        // 1) 系统提示词：同时交代「角色 + 输出格式（必须是 JSON 且含哪些字段）」
        String system = """
                你是一位资深英译中翻译。请把用户给的英文翻译成中文。
                只输出 JSON，不要输出任何其它文字。JSON 必须包含三个字段：
                en（原文）、zh（译文）、note（对译法的一句点评）。
                """;

        // 2) few-shot 少样本：给两个例子，示范「输入→判决」的形态
        List<Message> fewShot = List.of(
                new UserMessage("Good morning."),
                new AssistantMessage("""
                        {"en":"Good morning.","zh":"早上好。","note":"地道口语问候，直译最佳。"}"""),
                new UserMessage("It's a piece of cake."),
                new AssistantMessage("""
                        {"en":"It's a piece of cake.","zh":"小菜一碟。","note":"意译为成语，避免死译。"}"""));

        // 3) .entity(TranslationResult.class)：模型输出 JSON，框架自动反序列化成对象
        return chatClient
                .prompt()
                .system(system)
                .messages(fewShot)         // 注入示例消息
                .user(text)
                .call()
                .entity(TranslationResult.class);
    }
}