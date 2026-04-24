package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.config.DomainPromptConfig;
import cn.wzw.llm.study.llmstudy.dto.retrieval.GenerationReferenceBundle;
import cn.wzw.llm.study.llmstudy.dto.retrieval.ReferenceMaterial;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 公共控制器支持类：为 Chat 和 Generate Controller 提供共享的
 * chatId 标准化、用户消息构建、引用渲染等能力。
 */
@Component
public class ProRagControllerSupport {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[\\^c(\\d+)]");

    @Autowired
    private DomainPromptConfig domainPromptConfig;

    /**
     * 统一 chatId 标准化。
     *
     * @param chatId       原始 chatId
     * @param defaultScope "chat-" 或 "gen-"
     */
    public String normalizeChatId(String chatId, String defaultScope) {
        if (!StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("chatId 不能为空");
        }
        String trimmed = chatId.trim();
        if (trimmed.startsWith("chat-") || trimmed.startsWith("gen-")) {
            return trimmed;
        }
        return defaultScope + trimmed;
    }

    /**
     * 构建问答场景的用户消息（引用文献嵌入 prompt）。
     */
    public String buildChatUserMessage(String domain, String userQuestion, GenerationReferenceBundle bundle) {
        String chatPrompt = domainPromptConfig.getDomain(domain).chatPrompt();
        String documentContent = renderReferences(bundle);
        return String.format(chatPrompt, documentContent, userQuestion);
    }

    /**
     * 构建文档生成场景的用户消息。
     */
    public String buildGenerateUserMessage(String instruction, GenerationReferenceBundle referenceBundle, String domain) {
        if (!StringUtils.hasText(instruction)) {
            throw new IllegalArgumentException("instruction 不能为空");
        }

        List<String> mergedContents = referenceBundle.contents();
        String documentContent = mergedContents.isEmpty()
                ? "未检索到匹配材料，请根据已有要求输出基础框架，并把缺失信息标记为待补充。"
                : String.join("\n\n=========文档分隔线===========\n\n", mergedContents);
        String generatePrompt = domainPromptConfig.getDomain(domain).generatePrompt();
        return String.format(generatePrompt, documentContent, instruction.trim());
    }

    /**
     * 获取某个 domain 对应的默认文件名（生成稿文件名兜底）。
     * 仅暴露"取默认文件名"这一个能力，避免把 domainPromptConfig 整体暴露给上层。
     */
    public String getDefaultFileName(String domain) {
        return domainPromptConfig.getDomain(domain).defaultFileName();
    }

    /**
     * 把 references 的标题元数据与 contents 里对应同一下标的完整文本拼成 prompt。
     * references 与 contents 由 {@code hybridFusionDetailed} 严格 1:1 对齐，
     * LLM 看到的 [cN] 即前端 refId = cN。
     */
    public String renderReferences(GenerationReferenceBundle bundle) {
        List<ReferenceMaterial> references = bundle.referenceMaterials();
        List<String> contents = bundle.contents();
        if (references == null || references.isEmpty()) {
            return "（暂无可用参考文档）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < references.size(); i++) {
            ReferenceMaterial ref = references.get(i);
            String fullText = i < contents.size() ? contents.get(i) : ref.excerpt();
            sb.append('[').append(ref.refId()).append(']');
            sb.append(' ').append('(').append(ref.filename());
            if (ref.pageNumber() != null) {
                sb.append(" p.").append(ref.pageNumber());
            }
            if (StringUtils.hasText(ref.sectionPath())) {
                sb.append(" / ").append(ref.sectionPath());
            }
            if (StringUtils.hasText(ref.chunkType()) && !"TEXT".equalsIgnoreCase(ref.chunkType())) {
                sb.append(" · ").append(ref.chunkType());
            }
            sb.append(")\n");
            sb.append(fullText == null ? "" : fullText.trim());
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 从 LLM 回答中提取所有 [^cN] 引用角标，并按出现顺序过滤出 references 中真正存在的 refId。
     */
    public List<String> extractUsedRefIds(String answer, List<ReferenceMaterial> references) {
        if (!StringUtils.hasText(answer) || references == null || references.isEmpty()) {
            return List.of();
        }
        Set<String> validRefIds = references.stream()
                .map(ReferenceMaterial::refId)
                .collect(Collectors.toSet());

        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            String refId = "c" + matcher.group(1);
            if (validRefIds.contains(refId)) {
                seen.add(refId);
            }
        }
        return new ArrayList<>(seen);
    }
}
