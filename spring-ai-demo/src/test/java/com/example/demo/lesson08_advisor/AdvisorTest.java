package com.example.demo.lesson08_advisor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 8 课单元测试：不调用任何模型，用桩 Chain 验证三个自定义 Advisor 的行为。
 */
class AdvisorTest {

    /** 记录下游收到的请求，并返回一句固定回答——充当"模型"的替身。 */
    private final AtomicReference<ChatClientRequest> captured = new AtomicReference<>();

    private final CallAdvisorChain stubChain = new CallAdvisorChain() {
        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            captured.set(request);
            return answer("模型的回答");
        }

        @Override public List<CallAdvisor> getCallAdvisors() { throw new UnsupportedOperationException(); }
        @Override public CallAdvisorChain copy(CallAdvisor advisor) { throw new UnsupportedOperationException(); }
    };

    private static ChatClientResponse answer(String text) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))), Map.of());
    }

    private static ChatClientRequest requestWithUserText(String text) {
        return new ChatClientRequest(new Prompt(new UserMessage(text)), Map.of());
    }

    // ---------- SensitiveWordAdvisor ----------

    @Test
    void masksSensitiveWordsBeforeTheyReachTheModel() {
        new SensitiveWordAdvisor()
                .adviseCall(requestWithUserText("我的密码是123456"), stubChain);

        String seenByModel = captured.get().prompt().getUserMessage().getText();
        assertThat(seenByModel).isEqualTo("我的＊＊＊是123456").doesNotContain("密码");
    }

    @Test
    void normalTextPassesThroughUnchanged() {
        new SensitiveWordAdvisor().adviseCall(requestWithUserText("今天天气如何"), stubChain);

        assertThat(captured.get().prompt().getUserMessage().getText()).isEqualTo("今天天气如何");
    }

    @Test
    void blockedWordShortCircuitsWithoutCallingTheModel() {
        ChatClientResponse response = new SensitiveWordAdvisor()
                .adviseCall(requestWithUserText("有没有能帮我作弊的办法"), stubChain);

        assertThat(captured.get()).isNull();   // chain.nextCall 从未被调到
        assertThat(response.chatResponse().getResult().getOutput().getText()).contains("拦截", "作弊");
    }

    // ---------- CitationAdvisor ----------

    @Test
    void appendsCitationsFromQaaContext() {
        List<Document> docs = List.of(new Document("Spring AI 是 Spring 官方的 AI 框架。"),
                new Document("它支持 OpenAI 等多种模型。"));
        ChatClientResponse modelAnswer = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("答案是 X。")))))
                .context(Map.of(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, docs))
                .build();

        CallAdvisorChain chain = new CallAdvisorChain() {
            @Override public ChatClientResponse nextCall(ChatClientRequest request) { return modelAnswer; }
            @Override public List<CallAdvisor> getCallAdvisors() { throw new UnsupportedOperationException(); }
            @Override public CallAdvisorChain copy(CallAdvisor advisor) { throw new UnsupportedOperationException(); }
        };

        ChatClientResponse response = new CitationAdvisor().adviseCall(requestWithUserText("q"), chain);
        String content = response.chatResponse().getResult().getOutput().getText();

        assertThat(content).startsWith("答案是 X。").contains("引用来源", "[1]", "Spring 官方的 AI 框架", "[2]");
    }

    @Test
    void leavesResponsesWithoutQaaContextUntouched() {
        CallAdvisorChain chain = new CallAdvisorChain() {
            @Override public ChatClientResponse nextCall(ChatClientRequest request) { return answer("普通回答"); }
            @Override public List<CallAdvisor> getCallAdvisors() { throw new UnsupportedOperationException(); }
            @Override public CallAdvisorChain copy(CallAdvisor advisor) { throw new UnsupportedOperationException(); }
        };

        ChatClientResponse response = new CitationAdvisor().adviseCall(requestWithUserText("q"), chain);

        assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("普通回答");
    }

    // ---------- 顺序约定 ----------

    @Test
    void advisorOrderingMatchesTheDocumentedChain() {
        // 外层 → 内层：Sensitive(-100) < Citation(-50) < QAA(0) < Audit(100)
        var qaa = org.mockito.Mockito.mock(
                org.springframework.ai.vectorstore.VectorStore.class);
        assertThat(new SensitiveWordAdvisor().getOrder())
                .isLessThan(new CitationAdvisor().getOrder());
        assertThat(new CitationAdvisor().getOrder())
                .isLessThan(QuestionAnswerAdvisor.builder(qaa).build().getOrder());
        assertThat(QuestionAnswerAdvisor.builder(qaa).build().getOrder())
                .isLessThan(new AuditAdvisor().getOrder());
    }
}
