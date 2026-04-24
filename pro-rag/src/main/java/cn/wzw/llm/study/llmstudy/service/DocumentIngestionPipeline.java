package cn.wzw.llm.study.llmstudy.service;

import org.springframework.ai.document.Document;

import java.io.File;
import java.util.List;

/**
 * 文档入库流水线统一抽象。
 * 每种文件格式（PDF、DOCX 等）各自实现，由 {@link ProRagDocumentIngestionService}
 * 按 supports 匹配后调用 process。
 * <p>
 * 实现类可通过 Spring 的 {@code @Order} 注解控制匹配优先级，数值越小越优先。
 * 注入 {@code List<DocumentIngestionPipeline>} 时 Spring 会自动按 {@code @Order} 排序。
 */
public interface DocumentIngestionPipeline {

    /**
     * 该流水线是否支持处理此文件（通常按扩展名判断）。
     */
    boolean supports(File file);

    /**
     * 解析文件并产出分片后的 Document 列表。
     *
     * @param file    本地文件
     * @param profile 分块策略 profile 名称（如 pdf-text / pdf-scanned / word）
     * @return 分片列表（含 chunkId 和 metadata）
     */
    List<Document> process(File file, String profile) throws Exception;
}
