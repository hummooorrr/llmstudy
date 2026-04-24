package cn.wzw.llm.study.llmstudy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档解析开关与限流参数（结构化抽取、图片 VL 调用上限等）。
 */
@ConfigurationProperties(prefix = "pro-rag.parsing")
public class ParsingProperties {

    /** 是否启用结构化抽取（PDF 表格/图片、DOCX 表格/图片独立成 chunk）。关闭后走兼容老流程 */
    private boolean structuredEnabled = true;

    /** 每份文档最大抽取图片数（超过则仅保留前 N 张，防止 VL 调用爆炸） */
    private int maxImagesPerDoc = 20;

    /** 图片最小边长像素（低于阈值的图标 / 装饰元素跳过不入库） */
    private int minImageDimension = 80;

    /** VL 模型并发上限（共享信号量，控制扫描整页和单张图片 VL 调用的总并发数） */
    private int visionMaxConcurrency = 3;

    /** 图片 VL 描述 prompt */
    private String imagePrompt = "请用 1-2 句话简要描述这张图片的内容要点，如果是图表请说明核心趋势或结论，如果包含文字请提取关键文字。";

    /** PDF 扫描件整页 VL 识别 prompt（支持 TABLE 标记） */
    private String scannedPagePrompt = "请仔细识别并提取这张图片中的所有文字内容，保持原始格式和结构。如果页面中有表格，请将整张表格以 Markdown 表格形式重写，并用 <!--TABLE--> 和 <!--/TABLE--> 包裹。这是 PDF 的第 %d 页（共 %d 页）。";

    public boolean isStructuredEnabled() { return structuredEnabled; }
    public void setStructuredEnabled(boolean structuredEnabled) { this.structuredEnabled = structuredEnabled; }
    public int getMaxImagesPerDoc() { return maxImagesPerDoc; }
    public void setMaxImagesPerDoc(int maxImagesPerDoc) { this.maxImagesPerDoc = maxImagesPerDoc; }
    public int getMinImageDimension() { return minImageDimension; }
    public void setMinImageDimension(int minImageDimension) { this.minImageDimension = minImageDimension; }
    public String getImagePrompt() { return imagePrompt; }
    public void setImagePrompt(String imagePrompt) { this.imagePrompt = imagePrompt; }
    public int getVisionMaxConcurrency() { return visionMaxConcurrency; }
    public void setVisionMaxConcurrency(int visionMaxConcurrency) { this.visionMaxConcurrency = visionMaxConcurrency; }
    public String getScannedPagePrompt() { return scannedPagePrompt; }
    public void setScannedPagePrompt(String scannedPagePrompt) { this.scannedPagePrompt = scannedPagePrompt; }
}
