package cn.wzw.llm.study.llmstudy;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/model")
public class ChatModelController {

    @Autowired
    private ChatModel chatModel;

    @RequestMapping("/call/string")
    public String callString(String message) {
        return chatModel.call(message);
    }

    @RequestMapping("/stream/string")
    public Flux<String> callStreamString(String message, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        return chatModel.stream(message);
    }
}
