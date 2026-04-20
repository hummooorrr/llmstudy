package cn.wzw.llm.study.llmstudy.controller;


import cn.wzw.llm.study.llmstudy.service.LangChainAiService;
import cn.wzw.llm.study.llmstudy.tool.TemperatureTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LangChain4j 工具调用演示：低级别 ToolSpecification 与高级别 AiService 两种方式
 */
@RestController
@RequestMapping("/ToolController")
public class ToolController {

    @Autowired
    OpenAiChatModel chatModel;

    @RequestMapping("tool")
    public String tool() {
        //1、定义工具列表
        List<ToolSpecification> toolSpecifications = ToolSpecifications.toolSpecificationsFrom(TemperatureTools.class);
        //2.构造用户提示词
        UserMessage userMessage = UserMessage.from("2026年4月15日，武汉的气温怎样？");
        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(userMessage);
        //3. 创建ChatRequest，并指定工具列表
        ChatRequest request = ChatRequest.builder()
                .messages(userMessage)
                .toolSpecifications(toolSpecifications)
                .toolChoice(ToolChoice.AUTO)
                .build();
        //4. 调用模型
        ChatResponse response = chatModel.chat(request);
        AiMessage aiMessage = response.aiMessage();
        //5.把模型结果添加到chatMessages中
        chatMessages.add(aiMessage);

        //6.执行工具
        List<ToolExecutionRequest> toolExecutionRequests = response.aiMessage().toolExecutionRequests();
        toolExecutionRequests.forEach(toolExecutionRequest -> {
            ToolExecutor toolExecutor = new DefaultToolExecutor(new TemperatureTools(), toolExecutionRequest);
            System.out.println("execute tool " + toolExecutionRequest.name());
            String result = toolExecutor.execute(toolExecutionRequest, UUID.randomUUID().toString());
            ToolExecutionResultMessage toolExecutionResultMessages = ToolExecutionResultMessage.from(toolExecutionRequest, result);
            //7.把工具执行结果添加到chatMessages中
            chatMessages.add(toolExecutionResultMessages);
        });

        //8.重新构造ChatRequest，并使用之前的对话chatMessages，以及指定toolSpecifications
        ChatRequest finalRequest = ChatRequest.builder()
                .messages(chatMessages)
                .toolSpecifications(toolSpecifications)
                .build();

        //9. 再次调用模型，返回结果
        ChatResponse finalChatResponse = chatModel.chat(finalRequest);
        return finalChatResponse.aiMessage().text();
    }


    /**
     * 高级别api
     * @param response
     * @param msg
     * @return
     */
    @RequestMapping("/toolCalling")
    public String toolCalling(HttpServletResponse response, String msg) {
        response.setCharacterEncoding("UTF-8");

        LangChainAiService langChainAiService1 = AiServices.builder(LangChainAiService.class)
                .tools(new TemperatureTools())
                .chatModel(chatModel)
                .build();

        return langChainAiService1.chat("2025年11月11日，杭州的气温怎样？");
    }

}
