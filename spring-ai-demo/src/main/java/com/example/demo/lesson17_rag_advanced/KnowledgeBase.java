package com.example.demo.lesson17_rag_advanced;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;

/**
 * 第 17 课：业务 FAQ 知识库 —— 售后客服场景（与 lesson16 的工具场景同一业务，方便对照）。
 *
 * <p>与 lesson06 用 markdown 文件切块不同，这里把知识库<b>结构化</b>地放在代码里，
 * 每个 chunk 自带 metadata（docId / source / chunk 序号 / version）。业务 RAG 的
 * 引用溯源、增量更新都靠 metadata 驱动——lesson06 那种「裸文本切块」做不了这两件事。</p>
 *
 * <p><b>与 LangChain 对照</b>：metadata ≈ LangChain Document 的 <code>metadata</code> 字典；
 * docId 分组 ≈ <code>Document(id=...)</code> 的显式主键。</p>
 */
public final class KnowledgeBase {

    private KnowledgeBase() {
    }

    /** 一篇业务文档：多个 chunk，同属一个 docId（增量更新的操作单位） */
    public record FaqDoc(String docId, String source, List<String> chunks) {
    }

    /**
     * v1 版知识库：三篇文档。invoice 这篇在 {@link #INVOICE_V2} 里有新版，
     * 用于演示「增量灌库」——不是重复追加，而是替换同 docId 的旧 chunk。
     */
    public static final List<FaqDoc> V1 = List.of(
            new FaqDoc("refund-policy", "《售后退款政策》v1", List.of(
                    "签收后 7 天内支持无理由退款，商品需保持完好且不影响二次销售。",
                    "超过 7 天仅支持质量问题退款，需提供商品照片作为凭证，审核 1-3 个工作日。",
                    "退款原路退回，3-5 个工作日到账；优惠券抵扣部分按实际支付金额退还。")),
            new FaqDoc("shipping", "《发货与物流说明》v1", List.of(
                    "现货商品 48 小时内发货，偏远地区顺延 2 天；预售商品以商品页标注为准。",
                    "快递默认圆通，满 99 元包邮；发货后可在订单详情页查看物流单号。")),
            new FaqDoc("invoice", "《发票开具指南》v1", List.of(
                    "支持开具电子普通发票，下单时在结算页选择并填写抬头，收货后 24 小时内开出。")));

    /** invoice 的 v2 版：内容变了（新增专票说明）。增量灌库时用它替换 v1 的旧 chunk。 */
    public static final FaqDoc INVOICE_V2 = new FaqDoc("invoice", "《发票开具指南》v2", List.of(
            "支持开具电子普通发票与增值税专用发票，下单时在结算页选择并填写抬头。",
            "电子发票在收货后 24 小时内开出，可在「发票中心」自助下载；专票需额外提供税号与开户行信息。"));

    /**
     * 把一篇文档铺成 chunk 级 Document 列表（每条带完整溯源 metadata）。
     * docId 不带版本号——它就是「同一篇文档」的稳定主键，v1/v2 共用，替换时按它删旧。
     */
    public static List<Document> toDocuments(FaqDoc doc) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < doc.chunks().size(); i++) {
            // 复合 id 保证同一篇文档重复灌库时 id 稳定（v1/v2 也一致），
            // 这是向量库 delete 按主键替换的前提
            String chunkId = doc.docId() + "#" + i;
            Map<String, Object> metadata = Map.of(
                    "docId", doc.docId(),
                    "source", doc.source(),
                    "chunk", i,
                    "id", chunkId);
            docs.add(new Document(chunkId, doc.chunks().get(i), metadata));
        }
        return docs;
    }

    /** 整个知识库铺平成 chunk 列表 */
    public static List<Document> allV1() {
        return V1.stream().flatMap(d -> toDocuments(d).stream()).toList();
    }
}
