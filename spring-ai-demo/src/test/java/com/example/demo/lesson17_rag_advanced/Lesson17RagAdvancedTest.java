package com.example.demo.lesson17_rag_advanced;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 17 课单元测试：不联网、不调真模型。
 * 向量库用真实 SimpleVectorStore + 手写 bigram 假 embedding（词面即语义，
 * "退款"相关文本天然向量相近，向量路真实可区分）；ChatModel 用 Mockito 桩，
 * 拒答分支用 verify(call, never()) 证明请求根本没到模型。
 */
class Lesson17RagAdvancedTest {

    /**
     * 假 embedding：把文本的字符 bigram hash 进 64 维向量（命中置 1，归一化）。
     * 词面相近的文本向量就相近——离线可复现的"语义"。
     */
    static final class BigramEmbedding implements EmbeddingModel {
        private static final int DIM = 64;

        private float[] vec(String text) {
            float[] v = new float[DIM];
            for (String g : KeywordScorer.bigrams(text)) {
                v[Math.abs(g.hashCode()) % DIM] += 1f;
            }
            float norm = 0f;
            for (float x : v) {
                norm += x * x;
            }
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < DIM; i++) {
                    v[i] /= norm;
                }
            }
            return v;
        }

        @Override public float[] embed(Document document) { return vec(document.getText()); }
        @Override public float[] embed(String text) { return vec(text); }
        @Override public int dimensions() { return DIM; }
        @Override public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> inputs = request.getInstructions().stream().map(String::valueOf).toList();
            List<Embedding> list = new java.util.ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                list.add(new Embedding(vec(inputs.get(i)), i));
            }
            return new EmbeddingResponse(list);
        }
    }

    private Lesson17Controller controller;
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        // ChatClient.call() 内部会 mutate() 一次模型 options，mock 必须给个真实可 mutate 的实例
        when(chatModel.getOptions()).thenReturn(
                org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(
                        new AssistantMessage("根据资料[1]：退款 3-5 个工作日到账。")))));
        controller = new Lesson17Controller(chatModel, new BigramEmbedding());
    }

    /** 灌一次全量 v1 库（refund 3 段 + shipping 2 段 + invoice 1 段 = 6 chunk） */
    private void ingestOnce() {
        Map<String, Object> r = controller.ingest(null);
        assertThat(r.get("addedChunks")).isEqualTo(6);
        assertThat(r.get("skippedChunks")).isEqualTo(0);
    }

    // ---------- 关键词打分 ----------

    @Test
    void keywordScoreRanksRelevantTextHigher() {
        String refundText = "退款原路退回，3-5 个工作日到账。";
        String shippingText = "现货商品 48 小时内发货。";
        assertThat(KeywordScorer.score("退款多久到账", refundText))
                .isGreaterThan(KeywordScorer.score("退款多久到账", shippingText));
        assertThat(KeywordScorer.score("完全无关的查询词", shippingText)).isZero();
    }

    // ---------- RRF 融合 ----------

    @Test
    void rrfFusionRanksDocsHitByBothRoutesFirst() {
        ingestOnce();

        // 用 search 端点验证融合：两路都命中的 refund chunk 应排在 hybrid 首位
        Map<String, Object> out = controller.search("退款多久到账", 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hybrid = (List<Map<String, Object>>) out.get("hybrid");
        assertThat(hybrid).isNotEmpty();
        // 首位应是 refund-policy 的到账条款（关键词 + 语义双命中）
        assertThat(hybrid.get(0).get("id")).isEqualTo("refund-policy#2");
        // 融合分与双路排名都可解释
        assertThat((Double) hybrid.get(0).get("rerankScore")).isGreaterThan(0.0);
        assertThat(hybrid.get(0).get("keywordRank")).isEqualTo(1);
    }

    // ---------- 重排 ----------

    @Test
    void rerankRewardsExactPhraseAndChangesOrder() {
        HybridRetriever retriever = new HybridRetriever(
                SimpleVectorStore.builder(new BigramEmbedding()).build(), List.of());
        Document weak = new Document("a", "发货时效 48 小时，满 99 包邮。", Map.of());
        Document strong = new Document("b", "退款原路退回，3-5 个工作日到账。", Map.of());
        // 人为构造"融合序"把强文档排在后面
        List<HybridRetriever.FusedDoc> fused = List.of(
                new HybridRetriever.FusedDoc(weak, 0.02, 1, -1),
                new HybridRetriever.FusedDoc(strong, 0.016, 2, 1));

        List<HybridRetriever.RerankedDoc> reranked = retriever.rerank("退款多久到账", fused);

        assertThat(reranked.get(0).fused().doc().getId()).isEqualTo("b");
        // "退款…到账" 的 bigram 覆盖率 + 完整子串不存在（query 整串未出现）→ 纯覆盖率
        assertThat(reranked.get(0).rerankScore()).isGreaterThan(reranked.get(1).rerankScore());
        assertThat(reranked.get(0).rankBefore()).isEqualTo(2);   // 原名次 2
        assertThat(reranked.get(0).rankAfter()).isEqualTo(1);    // 重排后登顶
    }

    // ---------- 增量灌库：幂等跳过 ----------

    @Test
    void repeatedIngestSkipsExistingDocsIdempotently() {
        ingestOnce();
        Map<String, Object> second = controller.ingest(null);
        assertThat(second.get("addedChunks")).isEqualTo(0);
        assertThat(second.get("skippedChunks")).isEqualTo(6);
        assertThat(second.get("corpusSize")).isEqualTo(6);   // 不会像 lesson06 那样越灌越重
    }

    // ---------- 增量灌库：按 docId 替换 ----------

    @Test
    void updateReplacesOnlyTheTargetDoc() {
        ingestOnce();

        Map<String, Object> out = controller.ingest("invoice");

        assertThat(out.get("replacedChunks")).isEqualTo(1);  // v1 的 invoice 被删
        assertThat(out.get("addedChunks")).isEqualTo(2);     // v2 的两段灌入
        assertThat(out.get("corpusSize")).isEqualTo(7);      // 6 - 1 + 2

        // 走 ask 验证 v2 内容可检索、v1 内容已不在
        Map<String, Object> askV2 = controller.ask("增值税专用发票要提供什么", 0.05);
        assertThat(askV2.get("refused")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> citations = (List<Map<String, Object>>) askV2.get("citations");
        assertThat(citations).extracting(c -> c.get("docId")).contains("invoice");
        // v2 的两个 chunk 主键与 v1 相同（docId#序号），替换后 id 不变、内容已换
        Map<String, Object> searchOut = controller.search("发票", 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vectorOnly = (List<Map<String, Object>>) searchOut.get("vectorOnly");
        assertThat(vectorOnly).extracting(m -> m.get("id")).contains("invoice#0", "invoice#1");
    }

    // ---------- 拒答 ----------

    @Test
    void askRefusesWithoutCallingModelWhenScoresAreLow() {
        ingestOnce();

        // 高阈值 + 知识库外问题 → 拒答，且模型一次都没被调用
        Map<String, Object> out = controller.ask("公司董事会主席是谁", 0.95);

        assertThat(out.get("refused")).isEqualTo(true);
        assertThat((String) out.get("reason")).contains("低于阈值");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void emptyCorpusRefusesBeforeAnyRetrieval() {
        Map<String, Object> out = controller.ask("退款多久到账", 0.25);
        assertThat(out.get("refused")).isEqualTo(true);
        assertThat((String) out.get("reason")).contains("知识库为空");
    }

    // ---------- 引用溯源 ----------

    @Test
    void answerCarriesCitationsWithSourceAndChunk() {
        ingestOnce();

        Map<String, Object> out = controller.ask("退款多久到账", 0.25);

        assertThat(out.get("refused")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> citations = (List<Map<String, Object>>) out.get("citations");
        assertThat(citations).isNotEmpty();
        Map<String, Object> top = citations.get(0);
        assertThat(top.get("source")).isEqualTo("《售后退款政策》v1");
        assertThat(top.get("docId")).isEqualTo("refund-policy");
        assertThat(top.get("chunk")).isEqualTo(2);           // 到账条款是第 2 段
        assertThat((String) out.get("answer")).contains("工作日");
    }
}
