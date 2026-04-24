package cn.wzw.llm.study.llmstudy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存配置（本地 Caffeine）。
 */
@ConfigurationProperties(prefix = "pro-rag.cache")
public class CacheProperties {

    /** 检索结果缓存 TTL（秒），0 表示不缓存 */
    private int retrievalTtlSeconds = 300;

    /** 检索结果缓存最大条目数 */
    private int retrievalMaxSize = 500;

    /** 文件定位结果缓存 TTL（秒），0 表示不缓存 */
    private int locateTtlSeconds = 600;

    /** 文件定位结果缓存最大条目数 */
    private int locateMaxSize = 200;

    public int getRetrievalTtlSeconds() { return retrievalTtlSeconds; }
    public void setRetrievalTtlSeconds(int retrievalTtlSeconds) { this.retrievalTtlSeconds = retrievalTtlSeconds; }
    public int getRetrievalMaxSize() { return retrievalMaxSize; }
    public void setRetrievalMaxSize(int retrievalMaxSize) { this.retrievalMaxSize = retrievalMaxSize; }
    public int getLocateTtlSeconds() { return locateTtlSeconds; }
    public void setLocateTtlSeconds(int locateTtlSeconds) { this.locateTtlSeconds = locateTtlSeconds; }
    public int getLocateMaxSize() { return locateMaxSize; }
    public void setLocateMaxSize(int locateMaxSize) { this.locateMaxSize = locateMaxSize; }
}
