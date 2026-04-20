package cn.wzw.llm.study.llmstudy.dto.cleanup;

public enum CleanupTarget {
    UPLOADS,
    GENERATED,
    ALL;

    public boolean includesUploads() {
        return this == UPLOADS || this == ALL;
    }

    public boolean includesGenerated() {
        return this == GENERATED || this == ALL;
    }

    public static CleanupTarget from(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        return CleanupTarget.valueOf(value.trim().toUpperCase());
    }
}
