package com.example.demo.lesson08_advisor;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;

/**
 * 第 8 课：自定义 Advisor 之三 —— 给 RAG 回答追加「引用来源」。
 *
 * <p>{@link QuestionAnswerAdvisor} 在完成检索后，会把命中的文档放进
 * <b>advisor context</b>（key 是它的常量 {@link QuestionAnswerAdvisor#RETRIEVED_DOCUMENTS}，
 * 值是 {@code List<Document>}），但默认不会把来源暴露给调用方。
 * 本类演示 Advisor 之间的协作：在外层拿到 context 里的检索结果，
 * 把「引用来源」追加到模型回答的末尾再返回。</p>
 *
 * <p>顺序关系：本类 order = -50，比 QuestionAnswerAdvisor 的默认 order = 0 小 →
 * 更靠外层。于是执行流是：本类 adviseCall → chain.nextCall() 进到 QAA
 * （它做检索、改写 prompt、调模型，并把文档写进 context）→ 返回时本类
 * 从 <b>response.context()</b> 里取出文档做后处理。「外层 Advisor 处理内层 Advisor 的产出」
 * 是 Advisor 组合的经典模式。</p>
 */
public class CitationAdvisor implements CallAdvisor {

    /** 每条引用最多展示的字符数，避免长文档刷屏 */
    private static final int SNIPPET_LIMIT = 80;

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    /** 比 QuestionAnswerAdvisor（默认 0）更靠外层，才能在 nextCall 返回后做后处理。 */
    @Override
    public int getOrder() {
        return -50;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);

        List<Document> docs = (List<Document>) response.context()
                .get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (docs == null || docs.isEmpty()) {
            return response;   // 不带 QAA 的普通对话没有这个 key，原样放行
        }

        String citations = "\n\n---\n引用来源：\n" + IntStream.range(0, docs.size())
                .mapToObj(i -> {
                    String text = docs.get(i).getText();
                    String snippet = text.length() <= SNIPPET_LIMIT
                            ? text : text.substring(0, SNIPPET_LIMIT) + "…";
                    return "[%d] %s".formatted(i + 1, snippet.replace("\n", " "));
                })
                .collect(Collectors.joining("\n"));

        // 用追加后的文本重建响应（ChatResponse/Generation 都是不可变对象，标准做法是重建）
        ChatResponse original = response.chatResponse();
        Generation enriched = new Generation(new AssistantMessage(
                original.getResult().getOutput().getText() + citations));
        ChatResponse newChatResponse = new ChatResponse(List.of(enriched), original.getMetadata());

        return ChatClientResponse.builder()
                .chatResponse(newChatResponse)
                .context(response.context())
                .build();
    }
}
