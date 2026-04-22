package cn.wzw.llm.study.llmstudy.model;

/**
 * Chunk 内容类型：用于检索命中后的差异化展示。
 */
public enum ChunkType {
    TEXT,
    TABLE,
    IMAGE;

    public static ChunkType parse(Object raw) {
        if (raw == null) {
            return TEXT;
        }
        try {
            return ChunkType.valueOf(raw.toString().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TEXT;
        }
    }
}
