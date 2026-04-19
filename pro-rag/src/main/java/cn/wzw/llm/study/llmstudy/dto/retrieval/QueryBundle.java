package cn.wzw.llm.study.llmstudy.dto.retrieval;

import java.util.List;
import java.util.stream.Stream;

/**
 * 查询策略路由的输出结构。
 * 向量检索和关键词检索使用不同的查询列表，以适配 HyDE 等策略。
 */
public record QueryBundle(
        String originalQuery,
        List<String> vectorQueries,
        List<String> keywordQueries
) {
    public List<String> allQueries() {
        return Stream.concat(vectorQueries.stream(), keywordQueries.stream())
                .distinct()
                .toList();
    }
}
