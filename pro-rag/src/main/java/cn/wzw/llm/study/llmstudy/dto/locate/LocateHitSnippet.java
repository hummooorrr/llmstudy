package cn.wzw.llm.study.llmstudy.dto.locate;

public record LocateHitSnippet(
        String snippet,
        String source,
        String matchedQuery,
        String reason
) {
}
