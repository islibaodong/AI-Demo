package com.example.demo.lesson17_rag_advanced;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 第 17 课：混合检索 + RRF 融合 + 规则重排 —— 业务 RAG 的检索质量三件套。
 *
 * <p><b>1) 混合检索（Hybrid Search）</b>：两路并行——</p>
 * <ul>
 *   <li>向量路：{@code vectorStore.similaritySearch(...)}，语义相关性；</li>
 *   <li>关键词路：{@link KeywordScorer}（bigram 覆盖率），精确词命中。</li>
 * </ul>
 *
 * <p><b>2) RRF 融合（Reciprocal Rank Fusion）</b>：两路的<b>原始分数不可比</b>
 * （余弦相似度 ≈0.x，覆盖率 0~1，量纲完全不同），不能直接相加。
 * RRF 只看<b>排名</b>不看分数：每个文档的融合分 = Σ 1/(K + rank)，K 取 60（论文经验值）。
 * 两路都排前面的文档融合分最高——这就是"语义相近 <b>且</b> 词面命中"的段落。</p>
 *
 * <p><b>3) 重排（Rerank）</b>：召回（检索）追求"别漏"，topK 可以放宽；
 * 精排追求"排对"，用更重的模型对<b>候选</b>逐个精算。生产用 cross-encoder
 * （bge-reranker / Cohere Rerank），本课用词面规则演示——重排器是插拔件，
 * 接口不变、换实现即可，这也是把 rerank 独立成一个类的理由。</p>
 *
 * <p><b>与 LangChain 对照</b>：RRF ≈ <code>EnsembleRetriever</code> 的权重融合；
 * rerank ≈ <code>ContextualCompressionRetriever + Reranker 文档压缩器</code>
 * （LangChain 里重排作为"压缩/后处理"环节挂在检索器后面）。</p>
 */
public final class HybridRetriever {

    /** RRF 常数 K：排名越靠前贡献越大，K 越大排名差距越被抹平（60 是论文默认值） */
    static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final List<Document> corpus;

    public HybridRetriever(VectorStore vectorStore, List<Document> corpus) {
        this.vectorStore = vectorStore;
        this.corpus = corpus;
    }

    /** 一次融合检索的结果：分数 + 两路各自的排名（可解释性是排查检索质量的关键） */
    public record FusedDoc(Document doc, double rrfScore, int vectorRank, int keywordRank) {
    }

    /**
     * 混合检索：向量路 topK + 关键词路 topK → RRF 融合排序。
     *
     * @param vectorTopK 向量路召回数（宽召回，宁可多不可漏）
     * @param fuseTopN   融合后保留数（喂给重排/生成的量）
     */
    public List<FusedDoc> search(String query, int vectorTopK, int fuseTopN) {
        // 向量路：threshold 设 0（similarityThresholdAll）——召回阶段宁滥勿缺，筛选择后置
        List<Document> vectorHits = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(vectorTopK).similarityThresholdAll().build());
        // 关键词路：对全量语料打分（教学规模直接全扫；生产由搜索引擎的倒排索引承担）
        List<Document> keywordHits = KeywordScorer.search(query, corpus, vectorTopK);

        // RRF：docId → 融合分 + 两路排名（用 LinkedHashSet 去重保持首次出现序）
        Map<String, Double> fused = new HashMap<>();
        Map<String, Integer> vRank = new HashMap<>();
        Map<String, Integer> kRank = new HashMap<>();
        accumulate(fused, vRank, vectorHits);
        accumulate(fused, kRank, keywordHits);

        List<FusedDoc> results = new ArrayList<>();
        for (String id : fused.keySet()) {
            Document doc = findDoc(id, vectorHits, keywordHits);
            if (doc != null) {
                results.add(new FusedDoc(doc, fused.get(id),
                        vRank.getOrDefault(id, -1), kRank.getOrDefault(id, -1)));
            }
        }
        results.sort((a, b) -> Double.compare(b.rrfScore(), a.rrfScore()));
        return results.subList(0, Math.min(fuseTopN, results.size()));
    }

    /** 一路结果按排名累加 RRF 分（rank 从 1 开始：第 1 名贡献 1/(K+1)） */
    private static void accumulate(Map<String, Double> fused, Map<String, Integer> ranks, List<Document> hits) {
        Set<String> seen = new LinkedHashSet<>();
        for (Document d : hits) {
            if (!seen.add(d.getId())) {
                continue;
            }
            int rank = seen.size();
            fused.merge(d.getId(), 1.0 / (RRF_K + rank), Double::sum);
            ranks.putIfAbsent(d.getId(), rank);
        }
    }

    private static Document findDoc(String id, List<Document> a, List<Document> b) {
        for (Document d : a) {
            if (d.getId().equals(id)) {
                return d;
            }
        }
        for (Document d : b) {
            if (d.getId().equals(id)) {
                return d;
            }
        }
        return null;
    }

    // ---------- 重排（词面规则版，生产换 cross-encoder） ----------

    /** 重排单项：精排分（0~1 的 bigram 覆盖率）+ 重排前后的名次变化 */
    public record RerankedDoc(FusedDoc fused, double rerankScore, int rankBefore, int rankAfter) {
    }

    /**
     * 规则重排：按「query bigram 在文本中的覆盖率」精算，并奖励完整子串命中
     * （查询词整串出现在文本里 = 强信号）。输入是 RRF 融合结果，输出重排后的顺序。
     */
    public List<RerankedDoc> rerank(String query, List<FusedDoc> candidates) {
        List<RerankedDoc> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            FusedDoc c = candidates.get(i);
            double s = KeywordScorer.score(query, c.doc().getText());
            // 完整子串奖励：query 去空格后整串出现，覆盖率再 +0.2（封顶 1.0）
            if (c.doc().getText().contains(query.replaceAll("\\s+", "")) && !query.isBlank()) {
                s = Math.min(1.0, s + 0.2);
            }
            scored.add(new RerankedDoc(c, s, i + 1, 0));
        }
        scored.sort((a, b) -> Double.compare(b.rerankScore(), a.rerankScore()));
        List<RerankedDoc> out = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            RerankedDoc r = scored.get(i);
            out.add(new RerankedDoc(r.fused(), r.rerankScore(), r.rankBefore(), i + 1));
        }
        return out;
    }
}
