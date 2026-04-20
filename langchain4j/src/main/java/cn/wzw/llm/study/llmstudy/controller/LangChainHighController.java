package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.service.LangChainAiService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LangChain4j使用起来更加方便
 */
@RestController
@RequestMapping("/LangChainHighController")
public class LangChainHighController {

    @Autowired
    private LangChainAiService aiService;

    @RequestMapping("/chat")
    public String chat(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return aiService.chat("日本都有哪些美食？");
    }


    @RequestMapping("/structure1")
    public String structure1(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        LangChainAiService.Book books = aiService.getBooks();
        return books.toString();
    }
}
