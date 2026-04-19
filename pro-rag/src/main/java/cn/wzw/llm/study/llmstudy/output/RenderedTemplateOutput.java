package cn.wzw.llm.study.llmstudy.output;

public record RenderedTemplateOutput(
        String templateName,
        String outputFormat,
        String outputFilename,
        String outputFilePath,
        String previewContent
) {
}
