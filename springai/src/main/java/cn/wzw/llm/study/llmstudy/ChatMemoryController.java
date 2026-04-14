package cn.wzw.llm.study.llmstudy;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/ai/memory")
public class ChatMemoryController implements InitializingBean {

    @Autowired
    private ChatModel zhiPuAiChatModel;

    private ChatClient chatClient;


    @GetMapping("/chat")
    public String chat() {

        List<Message> messages = new ArrayList<>();

        //第一轮对话
        messages.add(new SystemMessage("你是一个游戏设计师"));
        messages.add(new UserMessage("我想设计一个回合制游戏"));
        ChatResponse chatResponse = zhiPuAiChatModel.call(new Prompt(messages));
        String content = chatResponse.getResult().getOutput().getText();
        System.out.println(content);
        System.out.println("======");

        messages.add(new AssistantMessage(content));

        //第二轮对话
        messages.add(new UserMessage("能帮我结合一些二次元的元素吗?"));
        chatResponse = zhiPuAiChatModel.call(new Prompt(messages));
        content = chatResponse.getResult().getOutput().getText();
        System.out.println(content);
        System.out.println("======");

        messages.add(new AssistantMessage(content));

        //第三轮对话
        messages.add(new UserMessage("那如果主要是针对女性玩家的游戏呢?有什么需要改进的？"));
        chatResponse = zhiPuAiChatModel.call(new Prompt(messages));
        content = chatResponse.getResult().getOutput().getText();
        System.out.println(content);
        System.out.println("======");

        return content;
    }

    @GetMapping("/chat1")
    public Flux<String> chat1(String message, String chatId, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream().content();
    }



    @Override
    public void afterPropertiesSet() throws Exception {
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(10).build();

        this.chatClient = ChatClient.builder(zhiPuAiChatModel)
                // 实现 Logger 的 Advisor
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                // 设置 ChatClient 中 ChatModel 的 Options 参数
                .build();
    }
}