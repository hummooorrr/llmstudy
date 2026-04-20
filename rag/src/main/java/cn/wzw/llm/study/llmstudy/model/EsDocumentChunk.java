package cn.wzw.llm.study.llmstudy.model;

import lombok.Data;

import java.util.Map;

/** ElasticSearch 文档块数据模型：存储分片后的文档内容及元数据 */
@Data
public class EsDocumentChunk {

    private String id;
    private String content;
    private Map<String, Object> metadata;
}