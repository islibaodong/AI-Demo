package com.example.demo.lesson17_rag_advanced;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 17 课：RAG 业务进阶 —— 从 lesson06 的"能检索"到业务级的"检索得准、答得可信、库能更新"。
 *
 * <p>四个业务能力，对应三个端点：</p>
 * <ol>
 *   <li><b>混合检索 + 重排</b>：{@code /lesson17/search} 三路对比（纯向量 / 纯关键词 / 融合+重排），
 *       能看到 RRF 融合分和重排前后的名次变化；</li>
 *   <li><b>增量灌库</b>：{@code /lesson17/ingest}——同 docId 的 chunk 复合 id 稳定，
 *       重复灌自动跳过；{@code ?update=invoice} 用 v2 内容替换 v1（先按主键 delete 再 add），
 *       不会像 lesson06 那样越灌越重、新旧混杂；</li>
 *   <li><b>拒答（宁可承认不知道）</b>：{@code /lesson17/ask}——检索后最高精排分低于阈值时
 *       <b>不调用模型</b>直接拒答。生产 RAG 的第一原则：检索不到就别让模型编；</li>
 *   <li><b>引用溯源</b>：回答附带 citations（source + chunk + 检索分数），
 *       用户可以核对"这句话出自哪篇文档"——metadata 在 KnowledgeBase 里就埋好了。</li>
 * </ol>
 *
 * <p><b>架构说明</b>：本课<b>自建</b>一个 SimpleVectorStore（不注入 lesson06 的 Bean），
 * 知识库独立、互不污染；语料列表由本课自己持有（关键词路要全量打分，
 * 生产上这一路由 Elasticsearch 的倒排索引承担，不占向量库）。</p>
 *
 * <p><b>与 LangChain 对照</b>：整体 ≈ <code>EnsembleRetriever + 重排压缩 + RetrievalQA</code>；
 * 拒答思想 ≈ 检索为空/低分时短路返回，不进生成环节；
 * 增量灌库 ≈ 向量库的 <code>delete(ids)</code> + <code>add_documents</code>。</p>
 */
@RestController
public class Lesson17Controller {

    /** 拒答阈值：重排后最高分（bigram 覆盖率 0~1）低于它就拒答，默认 0.25 */
    static final double DEFAULT_REFUSAL_THRESHOLD = 0.25;

    private final ChatClient client;
    private final VectorStore store;
    private final List<Document> corpus = new ArrayList<>();
    /** docId → 该文档当前占用的 chunk 主键（增量更新时按它 delete 旧 chunk） */
    private final Map<String, List<String>> docIndex = new HashMap<>();

    public Lesson17Controller(ChatModel chatModel, EmbeddingModel embeddingModel) {
        this.client = ChatClient.builder(chatModel).build();
        // 自建独立向量库：与 lesson06 的 Bean 隔离，灌什么由本课的 ingest 全权控制
        this.store = SimpleVectorStore.builder(embeddingModel).build();
    }

    // ---------- 1) 增量灌库 ----------

    /**
     * 增量灌库（需 API Key——chunk 要向量化）。可重复调用：
     * <ul>
     *   <li>不带参数：灌 v1 全量，已存在的 docId 自动跳过（幂等）；</li>
     *   <li>{@code ?update=invoice}：把 invoice 这篇替换成 v2——先按 docId 找到旧 chunk
     *       主键 {@code store.delete(ids)}，再 add 新 chunk。这就是"增量"：
     *       未动过的 refund-policy / shipping 一个字节都不重灌。</li>
     * </ul>
     * 对比 lesson06 的 ingest：那里每次全量 add，重复调用会让向量库里同一段话出现多份。
     */
    @GetMapping("/lesson17/ingest")
    public Map<String, Object> ingest(@RequestParam(required = false) String update) {
        // 确定本次要落库的文档集：增量更新时只处理指定的那一篇（用 v2 内容），否则全量 v1
        List<KnowledgeBase.FaqDoc> batch = new ArrayList<>();
        if (update != null && !update.isBlank()) {
            KnowledgeBase.FaqDoc v2 = KnowledgeBase.INVOICE_V2;
            if (!v2.docId().equals(update.trim())) {
                return Map.of("error", "本演示只有 invoice 有 v2 版本，请用 ?update=invoice");
            }
            batch.add(v2);
        }
        else {
            batch.addAll(KnowledgeBase.V1);
        }

        int added = 0;
        int skipped = 0;
        int replaced = 0;
        List<Document> toAdd = new ArrayList<>();
        for (KnowledgeBase.FaqDoc doc : batch) {
            List<Document> chunks = KnowledgeBase.toDocuments(doc);
            List<String> existing = docIndex.get(doc.docId());
            if (existing != null && !updateRequested(update)) {
                // 同 docId 已灌过且不是更新请求 → 幂等跳过（省一遍 embedding 调用）
                skipped += existing.size();
                continue;
            }
            if (existing != null) {
                // 增量更新：按主键删旧 chunk，再放新 chunk（未动过的文档不重灌）
                store.delete(existing);
                corpus.removeIf(d -> existing.contains(d.getId()));
                replaced += existing.size();
            }
            for (Document d : chunks) {
                toAdd.add(d);
            }
            added += chunks.size();
            docIndex.put(doc.docId(), chunks.stream().map(Document::getId).toList());
        }
        if (!toAdd.isEmpty()) {
            store.add(toAdd);       // 逐段向量化写入向量库（花钱的一步）
            corpus.addAll(toAdd);   // 语料列表同步，供关键词路打分
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("addedChunks", added);
        result.put("skippedChunks", skipped);
        result.put("replacedChunks", replaced);
        result.put("corpusSize", corpus.size());
        result.put("docs", List.copyOf(docIndex.keySet()));
        return result;
    }

    private static boolean updateRequested(String update) {
        return update != null && !update.isBlank();
    }

    // ---------- 2) 检索质量三路对比 ----------

    /**
     * 同一个问题，三种检索的对比：
     * <ul>
     *   <li><b>vectorOnly</b>：纯向量路（cosine 排序）——语义但丢精确词；</li>
     *   <li><b>keywordOnly</b>：纯关键词路（bigram 覆盖率）——精确但不懂同义改写；</li>
     *   <li><b>hybrid</b>：RRF 融合 + 规则重排，附融合分/两路排名/精排分——生产形态。</li>
     * </ul>
     * 用假中转站时 embedding 是常量向量，vectorOnly 会退化成"按插入序"——
     * 这正是"embedding 质量差时混合检索救场"的活教材。
     */
    @GetMapping("/lesson17/search")
    public Map<String, Object> search(
            @RequestParam(defaultValue = "退款多久到账") String q,
            @RequestParam(defaultValue = "3") int topK) {

        HybridRetriever retriever = new HybridRetriever(store, corpus);
        Map<String, Object> result = new LinkedHashMap<>();

        // 路线 1：纯向量
        List<Document> vectorHits = store.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .query(q).topK(topK).similarityThresholdAll().build());
        result.put("vectorOnly", vectorHits.stream().map(this::brief).toList());

        // 路线 2：纯关键词
        List<Document> keywordHits = KeywordScorer.search(q, corpus, topK);
        result.put("keywordOnly", keywordHits.stream().map(this::brief).toList());

        // 路线 3：RRF 融合 + 重排（带完整可解释信息）。
        // 融合候选放宽到 5：RRF 截断在重排之前的话，单路强命中的正确答案
        // （关键词中了、语义没中）会被"两路都沾边但都不强"的段落挤掉
        List<HybridRetriever.FusedDoc> fused = retriever.search(q, 5, 5);
        List<HybridRetriever.RerankedDoc> reranked = retriever.rerank(q, fused);
        result.put("hybrid", reranked.stream()
                .map(r -> {
                    Map<String, Object> m = brief(r.fused().doc());
                    m.put("rrfScore", round4(r.fused().rrfScore()));
                    m.put("vectorRank", r.fused().vectorRank());
                    m.put("keywordRank", r.fused().keywordRank());
                    m.put("rerankScore", round4(r.rerankScore()));
                    m.put("rankBefore", r.rankBefore());
                    m.put("rankAfter", r.rankAfter());
                    return m;
                })
                .toList());
        return result;
    }

    // ---------- 3) 业务问答：拒答 + 引用溯源 ----------

    /**
     * 完整业务问答：混合检索 → 重排 → （低分拒答）→ 带引用生成。
     * 返回 answer + citations（每条引用的 source/chunk/分数）+ retrieval 过程。
     * 试试知识库外的问题（如"老板是谁"）看拒答分支——请求不会走到模型。
     */
    @GetMapping("/lesson17/ask")
    public Map<String, Object> ask(
            @RequestParam String q,
            @RequestParam(defaultValue = "0.25") double threshold) {

        HybridRetriever retriever = new HybridRetriever(store, corpus);
        // 宽召回（两路各 5）→ 宽融合（候选 5）→ 精排 → 最后才截断给生成的量（top3）。
        // 顺序不能反：截断若发生在重排之前，单路强命中的正确答案会被挤掉（本课实测）。
        List<HybridRetriever.FusedDoc> fused = retriever.search(q, 5, 5);
        List<HybridRetriever.RerankedDoc> reranked = retriever.rerank(q, fused);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", q);

        // 拒答判断：向量库还没灌、或最高精排分低于阈值——不调用模型，直接承认不知道
        if (corpus.isEmpty()) {
            result.put("refused", true);
            result.put("reason", "知识库为空，请先 GET /lesson17/ingest 灌库");
            return result;
        }
        double best = reranked.isEmpty() ? 0.0 : reranked.get(0).rerankScore();
        if (best < threshold) {
            result.put("refused", true);
            result.put("reason", ("最高检索分 %.2f 低于阈值 %.2f，知识库中没有足够相关的资料，"
                    + "宁可拒答也不让模型编造。").formatted(best, threshold));
            result.put("topScore", round4(best));
            return result;
        }

        // Augment：带引用编号的资料拼进系统提示（[1][2]... 便于模型在回答中标注出处）。
        // 零分段落不进提示词—— rerank 的另一个作用：把不相关的候选过滤在生成之前
        List<HybridRetriever.RerankedDoc> relevant = reranked.stream()
                .filter(r -> r.rerankScore() > 0)
                .limit(3)
                .toList();
        StringBuilder context = new StringBuilder();
        List<Map<String, Object>> citations = new ArrayList<>();
        for (int i = 0; i < relevant.size(); i++) {
            HybridRetriever.RerankedDoc r = relevant.get(i);
            Document d = r.fused().doc();
            context.append("[").append(i + 1).append("] ").append(d.getText()).append("\n\n");
            Map<String, Object> cite = new LinkedHashMap<>();
            cite.put("ref", i + 1);
            cite.put("source", d.getMetadata().get("source"));
            cite.put("docId", d.getMetadata().get("docId"));
            cite.put("chunk", d.getMetadata().get("chunk"));
            cite.put("rerankScore", round4(r.rerankScore()));
            citations.add(cite);
        }

        String system = """
                你是售后客服，请严格依据下面的资料回答用户问题，并在答案末尾用 [编号] 标注引用了哪几条资料。
                资料：
                %s
                要求：只根据资料作答；资料里没有的信息，明确说"资料中没有提到"，不要编造。
                """.formatted(context);

        // Generate：走到这里才花钱
        String answer = client.prompt().system(system).user(q).call().content();

        result.put("refused", false);
        result.put("answer", answer);
        result.put("citations", citations);
        return result;
    }

    // ---------- 小工具 ----------

    /** chunk 摘要：主键 + 来源 + 文本截断（响应里别把整段话都塞回去） */
    private Map<String, Object> brief(Document d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("source", d.getMetadata().get("source"));
        String text = d.getText();
        m.put("text", text.length() > 40 ? text.substring(0, 40) + "…" : text);
        return m;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
