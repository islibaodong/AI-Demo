package com.example.demo.lesson17_rag_advanced;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.document.Document;

/**
 * 第 17 课：关键词检索路 —— 纯本地的词面打分（BM25 的极简版）。
 *
 * <p><b>为什么向量检索之外还需要关键词检索（混合检索的动机）：</b></p>
 * <ul>
 *   <li>向量检索擅长语义（"退款" ≈ "退货返钱"），但对<b>精确词</b>不敏感：
 *       型号、单号、专有名词（"TT-9042"、"增值税专用发票"）在 embedding 空间里
 *       和其他词混在一起，常召回一堆"语义差不多"但没命中关键词的段落；</li>
 *   <li>embedding 服务本身可能降级/质量差（本课的假中转站就返回<b>常量向量</b>，
 *       所有段落分数完全相同，向量检索直接退化成"按插入序取前 K"）——
 *       此时关键词路是唯一的救场者；</li>
 *   <li>生产上关键词路通常是 BM25（Elasticsearch/OpenSearch），这里是教学极简版。</li>
 * </ul>
 *
 * <p><b>中文怎么分词：</b>不上分词器，用<b>字符 bigram</b>（相邻两字一组）。
 * "退款政策" → [退款, 款政, 政策]。中文关键词查询绝大多数是 2~4 字词，
 * bigram 重合度足以近似词面匹配，且零依赖、可离线测试。</p>
 *
 * <p><b>与 LangChain 对照</b>：混合检索 ≈ <code>EnsembleRetriever</code>
 * （BM25Retriever + VectorStoreRetriever 加权融合）。</p>
 */
public final class KeywordScorer {

    private KeywordScorer() {
    }

    /** 提取字符 bigram 集合（去重）。长度 <2 的串返回单字集合兜底。 */
    public static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        String t = text == null ? "" : text.replaceAll("\\s+", "");
        if (t.length() < 2) {
            if (!t.isEmpty()) {
                grams.add(t);
            }
            return grams;
        }
        for (int i = 0; i < t.length() - 1; i++) {
            grams.add(t.substring(i, i + 2));
        }
        return grams;
    }

    /**
     * 词面打分：query 的每个 bigram 在目标文本中出现则计 1 分，
     * 除以 query 的 bigram 总数 → [0,1] 的<b>覆盖率</b>分数（可跨文档比较、可设阈值）。
     */
    public static double score(String query, String text) {
        Set<String> qGrams = bigrams(query);
        if (qGrams.isEmpty()) {
            return 0.0;
        }
        Set<String> tGrams = bigrams(text);
        long hits = qGrams.stream().filter(tGrams::contains).count();
        return (double) hits / qGrams.size();
    }

    /** 对候选段落做关键词打分，返回按分数降序的列表（供 RRF 融合取排名用） */
    public static List<Document> search(String query, List<Document> candidates, int topK) {
        List<Document> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> Double.compare(score(query, b.getText()), score(query, a.getText())));
        return ranked.subList(0, Math.min(topK, ranked.size()));
    }
}
