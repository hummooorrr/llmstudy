package cn.wzw.llm.study.llmstudy.splitter;

import cn.wzw.llm.study.llmstudy.config.ChunkingProperties;
import cn.wzw.llm.study.llmstudy.model.ChunkMetadataKeys;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据 profile 名称（例如 markdown / word / pdf-text / pdf-scanned / image）
 * 返回对应策略的 TextSplitter，屏蔽 splitter 实例化细节。
 */
@Component
public class SplitterFactory {

    public static final String PROFILE_MARKDOWN = "markdown";
    public static final String PROFILE_WORD = "word";
    public static final String PROFILE_PDF_TEXT = "pdf-text";
    public static final String PROFILE_PDF_SCANNED = "pdf-scanned";
    public static final String PROFILE_IMAGE = "image";
    public static final String PROFILE_DEFAULT = "default";

    private final ChunkingProperties chunkingProperties;

    public SplitterFactory(ChunkingProperties chunkingProperties) {
        this.chunkingProperties = chunkingProperties;
    }

    /**
     * 根据 profile 名称创建 splitter。
     */
    public TextSplitter create(String profileName) {
        ChunkingProperties.Profile profile = chunkingProperties.resolveProfile(profileName);
        return instantiate(profile);
    }

    /**
     * 直接按 profile 对象创建。
     */
    public TextSplitter create(ChunkingProperties.Profile profile) {
        return instantiate(profile);
    }

    /**
     * 批量切分，同时把 profile 参数写回每个 chunk 的 metadata，便于事后评估。
     */
    public List<Document> split(List<Document> documents, String profileName) {
        if (CollectionUtils.isEmpty(documents)) {
            return List.of();
        }
        ChunkingProperties.Profile profile = chunkingProperties.resolveProfile(profileName);
        TextSplitter splitter = instantiate(profile);
        List<Document> chunks = splitter.apply(documents);
        if (chunks == null) {
            return List.of();
        }
        List<Document> enriched = new ArrayList<>(chunks.size());
        String resolvedName = profileName == null || profileName.isBlank() ? PROFILE_DEFAULT : profileName;
        for (Document chunk : chunks) {
            Map<String, Object> metadata = chunk.getMetadata() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(chunk.getMetadata());
            metadata.putIfAbsent(ChunkMetadataKeys.CHUNK_PROFILE, resolvedName);
            metadata.putIfAbsent(ChunkMetadataKeys.CHUNK_SIZE, profile.getChunkSize());
            metadata.putIfAbsent(ChunkMetadataKeys.CHUNK_OVERLAP, profile.getOverlap());
            enriched.add(new Document(chunk.getText(), metadata));
        }
        return enriched;
    }

    public ChunkingProperties.Profile resolveProfile(String profileName) {
        return chunkingProperties.resolveProfile(profileName);
    }

    private TextSplitter instantiate(ChunkingProperties.Profile profile) {
        String strategy = profile.getStrategy() == null ? "overlap-paragraph" : profile.getStrategy().toLowerCase();
        return switch (strategy) {
            case "markdown-header" -> buildMarkdownSplitter(profile);
            case "word-header" -> buildWordSplitter(profile);
            case "sentence-window" -> new SentenceWindowSplitter(profile.getSentenceWindowSize());
            default -> new OverlapParagraphTextSplitter(profile.getChunkSize(), profile.getOverlap());
        };
    }

    private MarkdownHeaderTextSplitter buildMarkdownSplitter(ChunkingProperties.Profile profile) {
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> configured = profile.getHeaders();
        List<String> effective = CollectionUtils.isEmpty(configured)
                ? List.of("# ", "## ", "### ")
                : configured;
        int level = 1;
        for (String marker : effective) {
            headers.put(marker, "h" + level);
            level++;
        }
        return new MarkdownHeaderTextSplitter(
                headers,
                profile.isReturnEachParagraph(),
                profile.isStripHeadings(),
                profile.isParentChildModel()
        );
    }

    private WordHeaderTextSplitter buildWordSplitter(ChunkingProperties.Profile profile) {
        List<Integer> levels = CollectionUtils.isEmpty(profile.getHeadingLevels())
                ? List.of(1, 2, 3)
                : profile.getHeadingLevels();
        return new WordHeaderTextSplitter(
                levels,
                profile.isReturnEachParagraph(),
                profile.isStripHeadings(),
                profile.isParentChildModel(),
                profile.getChunkSize(),
                profile.getOverlap()
        );
    }
}
