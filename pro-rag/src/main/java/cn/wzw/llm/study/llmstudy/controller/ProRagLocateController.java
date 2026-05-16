package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.dto.locate.LocateResultItem;
import cn.wzw.llm.study.llmstudy.service.ProRagRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文件定位接口
 * 根据用户问题，精准定位相关内容所在的文件全路径
 */
@RestController
@RequestMapping("/pro-rag")
@Slf4j
public class ProRagLocateController {

    @Autowired
    private ProRagRetrievalService proRagRetrievalService;

    /**
     * 根据问题定位相关文件
     *
     * @param query 用户问题
     * @return 按相关度排序的文件路径列表，补充命中片段和命中原因
     */
    @GetMapping("/locate")
    public List<LocateResultItem> locate(@RequestParam("query") String query) throws Exception {
        log.info("[Locate] 文件定位请求开始: queryLength={}", query.length());
        List<LocateResultItem> result = proRagRetrievalService.locateFiles(query);
        log.info("[Locate] 文件定位完成: resultCount={}", result.size());
        return result;
    }
}
