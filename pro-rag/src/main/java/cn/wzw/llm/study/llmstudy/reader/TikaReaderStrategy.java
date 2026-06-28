package cn.wzw.llm.study.llmstudy.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Tika 通用文件读取策略：通过 Apache Tika 解析 Word 等 Office 文档 */
@Component
public class TikaReaderStrategy implements DocumentReaderStrategy {

    @Override
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".doc") || name.endsWith(".docx");
    }

    @Override
    public List<Document> read(File file) throws IOException {
        // POI zip-bomb 阈值已在 ProRagApplication 启动时统一设置，这里无需重复设置。
        Resource resource = new FileSystemResource(file);
        return new TikaDocumentReader(resource).get();
    }
}