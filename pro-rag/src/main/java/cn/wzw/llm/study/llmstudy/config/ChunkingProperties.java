package cn.wzw.llm.study.llmstudy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块参数配置。
 * default 为所有未匹配到 profile 的默认策略；profiles 按文件类型/业务场景覆盖。
 */
@ConfigurationProperties(prefix = "pro-rag.chunking")
public class ChunkingProperties {

    private Profile defaultProfile = new Profile();

    private Map<String, Profile> profiles = new LinkedHashMap<>();

    public Profile getDefault() {
        return defaultProfile;
    }

    public void setDefault(Profile defaultProfile) {
        this.defaultProfile = defaultProfile;
    }

    public Profile getDefaultProfile() {
        return defaultProfile;
    }

    public Map<String, Profile> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, Profile> profiles) {
        this.profiles = profiles;
    }

    /**
     * 按 profile 名称查，找不到回退到默认。
     */
    public Profile resolveProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return defaultProfile;
        }
        Profile profile = profiles.get(profileName);
        return profile != null ? profile : defaultProfile;
    }

    public static class Profile {
        /** overlap-paragraph / markdown-header / word-header / sentence-window */
        private String strategy = "overlap-paragraph";
        private int chunkSize = 400;
        private int overlap = 100;
        private List<String> headers = List.of("# ", "## ", "### ");
        private List<Integer> headingLevels = List.of(1, 2, 3);
        private boolean stripHeadings = false;
        private boolean parentChildModel = true;
        private boolean returnEachParagraph = false;
        private int sentenceWindowSize = 2;

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getOverlap() { return overlap; }
        public void setOverlap(int overlap) { this.overlap = overlap; }
        public List<String> getHeaders() { return headers; }
        public void setHeaders(List<String> headers) { this.headers = headers; }
        public List<Integer> getHeadingLevels() { return headingLevels; }
        public void setHeadingLevels(List<Integer> headingLevels) { this.headingLevels = headingLevels; }
        public boolean isStripHeadings() { return stripHeadings; }
        public void setStripHeadings(boolean stripHeadings) { this.stripHeadings = stripHeadings; }
        public boolean isParentChildModel() { return parentChildModel; }
        public void setParentChildModel(boolean parentChildModel) { this.parentChildModel = parentChildModel; }
        public boolean isReturnEachParagraph() { return returnEachParagraph; }
        public void setReturnEachParagraph(boolean returnEachParagraph) { this.returnEachParagraph = returnEachParagraph; }
        public int getSentenceWindowSize() { return sentenceWindowSize; }
        public void setSentenceWindowSize(int sentenceWindowSize) { this.sentenceWindowSize = sentenceWindowSize; }
    }
}
