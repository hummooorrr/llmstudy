package cn.wzw.llm.study.llmstudy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索/融合/重排参数集中配置。
 */
@ConfigurationProperties(prefix = "pro-rag.retrieval")
public class RetrievalProperties {

    private int vectorTopK = 10;
    private int keywordTopK = 5;
    private double similarityThreshold = 0.35;
    private int maxRewriteQueries = 3;
    private int directiveVectorTopK = 8;
    private double directiveSimilarityThreshold = 0.20;

    private Locate locate = new Locate();
    private Rerank rerank = new Rerank();

    public int getVectorTopK() { return vectorTopK; }
    public void setVectorTopK(int vectorTopK) { this.vectorTopK = vectorTopK; }
    public int getKeywordTopK() { return keywordTopK; }
    public void setKeywordTopK(int keywordTopK) { this.keywordTopK = keywordTopK; }
    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    public int getMaxRewriteQueries() { return maxRewriteQueries; }
    public void setMaxRewriteQueries(int maxRewriteQueries) { this.maxRewriteQueries = maxRewriteQueries; }
    public int getDirectiveVectorTopK() { return directiveVectorTopK; }
    public void setDirectiveVectorTopK(int directiveVectorTopK) { this.directiveVectorTopK = directiveVectorTopK; }
    public double getDirectiveSimilarityThreshold() { return directiveSimilarityThreshold; }
    public void setDirectiveSimilarityThreshold(double directiveSimilarityThreshold) { this.directiveSimilarityThreshold = directiveSimilarityThreshold; }
    public Locate getLocate() { return locate; }
    public void setLocate(Locate locate) { this.locate = locate; }
    public Rerank getRerank() { return rerank; }
    public void setRerank(Rerank rerank) { this.rerank = rerank; }

    public static class Locate {
        private int vectorTopK = 10;
        private int keywordTopK = 6;
        private double similarityThreshold = 0.35;
        private int snippetLimit = 3;

        public int getVectorTopK() { return vectorTopK; }
        public void setVectorTopK(int vectorTopK) { this.vectorTopK = vectorTopK; }
        public int getKeywordTopK() { return keywordTopK; }
        public void setKeywordTopK(int keywordTopK) { this.keywordTopK = keywordTopK; }
        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
        public int getSnippetLimit() { return snippetLimit; }
        public void setSnippetLimit(int snippetLimit) { this.snippetLimit = snippetLimit; }
    }

    public static class Rerank {
        private int rrfK = 60;
        private int finalTopK = 8;

        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
        public int getFinalTopK() { return finalTopK; }
        public void setFinalTopK(int finalTopK) { this.finalTopK = finalTopK; }
    }
}
