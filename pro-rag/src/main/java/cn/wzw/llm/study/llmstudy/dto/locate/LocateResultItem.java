package cn.wzw.llm.study.llmstudy.dto.locate;

import java.util.List;

public record LocateResultItem(
        String filePath,
        String filename,
        double score,
        int matchedChunks,
        List<String> matchedQueries,
        List<String> hitReasons,
        List<LocateHitSnippet> hitSnippets
) {
}
