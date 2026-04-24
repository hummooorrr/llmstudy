package cn.wzw.llm.study.llmstudy.rerank;

import cn.wzw.llm.study.llmstudy.util.ProRagRerankUtil.FusedChunk;

import java.util.List;

/**
 * Rerank 供应商接口。
 * 不同 Rerank API（智谱、Cohere、本地模型等）统一实现此接口，
 * 通过配置或 Spring 条件注入切换。
 */
public interface RerankProvider {

    /**
     * 对候选 chunk 做精排，返回 topK 条结果，保持原始顺序。
     *
     * @param candidates 候选 chunk 列表（含 docId/text/metadata/score）
     * @param query      用户原始查询
     * @param topK       最终返回条数
     * @return 重排后的 topK 条结果
     */
    List<FusedChunk> rerank(List<FusedChunk> candidates, String query, int topK);
}
