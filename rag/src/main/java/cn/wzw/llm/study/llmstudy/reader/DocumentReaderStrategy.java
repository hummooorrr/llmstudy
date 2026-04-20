package cn.wzw.llm.study.llmstudy.reader;

import org.springframework.ai.document.Document;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** 文档读取策略接口：定义文件支持判断和文档读取的统一契约 */
public interface DocumentReaderStrategy {
    /**
     * 判断是否支持该文件
     */
    boolean supports(File file);

    /**
     * 读取文件并返回 Document 列表
     */
    List<Document> read(File file) throws IOException;
}