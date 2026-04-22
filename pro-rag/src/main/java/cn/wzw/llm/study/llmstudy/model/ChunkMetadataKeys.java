package cn.wzw.llm.study.llmstudy.model;

/**
 * chunk metadata 统一 key 常量，避免散落字符串拼写不一致。
 */
public final class ChunkMetadataKeys {

    public static final String FILENAME = "filename";
    public static final String FILE_PATH = "filePath";
    public static final String SOURCE_TYPE = "sourceType";
    public static final String CHUNK_ID = "chunkId";

    public static final String CHUNK_TYPE = "chunkType";
    public static final String PAGE_NUMBER = "pageNumber";
    public static final String SECTION_PATH = "sectionPath";
    public static final String ASSET_PATH = "assetPath";
    public static final String ASSET_DESCRIPTION = "assetDescription";

    public static final String CHUNK_PROFILE = "chunkProfile";
    public static final String CHUNK_SIZE = "chunkSize";
    public static final String CHUNK_OVERLAP = "chunkOverlap";
    public static final String PARENT_CHUNK_ID = "parentChunkId";
    public static final String CHILD_CHUNK_IDS = "childChunkIds";

    private ChunkMetadataKeys() {
    }
}
