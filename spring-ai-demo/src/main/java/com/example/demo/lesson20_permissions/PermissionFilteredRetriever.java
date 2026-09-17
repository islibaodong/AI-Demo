package com.example.demo.lesson20_permissions;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;

import com.example.demo.lesson17_rag_advanced.KeywordScorer;

/**
 * 第 20 课：检索层权限过滤 —— 数据权限在 RAG 里的正确落点。
 *
 * <p><b>核心原则：权限过滤必须发生在检索层（取数之前），不能靠提示词。</b>
 * 两种做法的差别是安全级别的差别：</p>
 * <ul>
 *   <li>✗ 先把全部资料（含机密）塞进提示词，再叮嘱模型"不要透露机密"——
 *       提示词不是安全边界，一次注入（lesson13）就全泄了；机密数据还进了上下文和日志；</li>
 *   <li>✓ 检索时先按用户密级过滤候选集，<b>无权限的内容根本不进入流程</b>——
 *       模型看不见的东西就泄露不了，这是唯一可靠的做法。</li>
 * </ul>
 *
 * <p>实现上就是一行 filter：候选语料按 metadata 的 {@code visibility} 与用户密级比较，
 * 过滤完再交给普通检索（本课复用 lesson17 的 {@link KeywordScorer} 关键词路，
 * 生产里对应向量库的 metadata filter：如 pgvector {@code WHERE visibility <= ?}）。</p>
 *
 * <p><b>与 LangChain 对照</b>：≈ SelfQueryRetriever 的 metadata filter，或
 * VectorStore 的 {@code search_kwargs={"filter": {...}}}——但那里过滤的是"业务标签"，
 * 这里过滤的是"安全密级"，语义完全不同：前者影响质量，后者是安全边界。</p>
 */
public final class PermissionFilteredRetriever {

    private PermissionFilteredRetriever() {
    }

    /** 文档密级 metadata 的 key */
    public static final String VISIBILITY_KEY = "visibility";

    /** 读文档密级；没标密级的一律按最机密处理（默认拒绝，不能默认公开） */
    public static UserPrincipal.DataLevel levelOf(Document doc) {
        Object v = doc.getMetadata().get(VISIBILITY_KEY);
        return (v == null || !StringUtils.hasText(v.toString()))
                ? UserPrincipal.DataLevel.CONFIDENTIAL
                : UserPrincipal.DataLevel.of(v.toString());
    }

    /** 先按密级过滤候选集，再做关键词检索——无权限文档根本不参与打分 */
    public static List<Document> searchFor(UserPrincipal user, String query, List<Document> corpus, int topK) {
        List<Document> allowed = corpus.stream()
                .filter(d -> user.canSee(levelOf(d)))
                .toList();
        return KeywordScorer.search(query, allowed, topK);
    }
}
