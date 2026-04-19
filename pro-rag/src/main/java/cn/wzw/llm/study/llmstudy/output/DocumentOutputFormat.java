package cn.wzw.llm.study.llmstudy.output;

public enum DocumentOutputFormat {
    MARKDOWN("markdown", ".md"),
    DOCX("docx", ".docx");

    private final String code;
    private final String extension;

    DocumentOutputFormat(String code, String extension) {
        this.code = code;
        this.extension = extension;
    }

    public String code() {
        return code;
    }

    public String extension() {
        return extension;
    }

    public static DocumentOutputFormat from(String value) {
        if (value == null || value.isBlank()) {
            return MARKDOWN;
        }
        for (DocumentOutputFormat format : values()) {
            if (format.code.equalsIgnoreCase(value.trim())) {
                return format;
            }
        }
        throw new IllegalArgumentException("不支持的输出格式: " + value + "，仅支持 markdown 或 docx");
    }
}
