package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.service.ProRagElasticSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维类接口：仅限内网调用。升级 ES mapping 或重建索引时使用。
 */
@RestController
@RequestMapping("/pro-rag/admin")
public class ProRagAdminController {

    @Autowired
    private ProRagElasticSearchService proRagElasticSearchService;

    /**
     * 在现有索引上追加新 metadata 字段映射（增量 put-mapping），不会影响老数据。
     */
    @PostMapping("/reindex-mapping")
    public Map<String, Object> reindexMapping() {
        proRagElasticSearchService.ensureMetadataMapping();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("message", "metadata mapping 已追加，如字段冲突请改用 /admin/recreate-index（会清空数据）");
        return result;
    }

    /**
     * 危险操作：删除索引后按最新 mapping 重建，调用方需重新上传入库全部文件。
     */
    @PostMapping("/recreate-index")
    public Map<String, Object> recreateIndex() throws Exception {
        proRagElasticSearchService.recreateIndex();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("message", "索引已按最新 mapping 重建，请调用 /pro-rag/reingest 逐个文件补录。");
        return result;
    }
}
