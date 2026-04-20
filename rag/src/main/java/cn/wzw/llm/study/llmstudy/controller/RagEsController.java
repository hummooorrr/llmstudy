package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import cn.wzw.llm.study.llmstudy.service.DocumentReaderStrategySelector;
import cn.wzw.llm.study.llmstudy.service.ElasticSearchService;
import cn.wzw.llm.study.llmstudy.splitter.OverlapParagraphTextSplitter;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;

/** RAG ElasticSearch 入库与检索接口：将文档分片写入 ES 并进行关键词搜索 */
@RestController
@RequestMapping("/rag/es")
public class RagEsController {


    @Autowired
    private DocumentReaderStrategySelector documentReaderStrategySelector;

    @Autowired
    private ElasticSearchService elasticSearchService;

    @RequestMapping("write")
    public String write(String filePath) throws Exception {
        // 1. 加载文档
        List<Document> documents = documentReaderStrategySelector.read(new File(filePath));

        // 2. 文本清洗
//        documents = DocumentCleaner.cleanDocuments(documents);

        // 3. 文档分片
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(
                // 每块最大字符数
                200,
                // 块之间重叠 100 字符
                50
        );
        List<Document> apply = splitter.apply(documents);

        // 4. 存储到ES
        List<EsDocumentChunk> esDocs = apply.stream().map(doc -> {
            EsDocumentChunk es = new EsDocumentChunk();
            es.setId(doc.getId());
            es.setContent(doc.getText());
            es.setMetadata(doc.getMetadata());
            return es;
        }).toList();

        elasticSearchService.bulkIndex(esDocs);
        return "success";
    }

    @RequestMapping("search")
    public List<EsDocumentChunk> search(String keyword) throws Exception {
        return elasticSearchService.searchByKeyword(keyword);
    }
}
