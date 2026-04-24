package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.config.CacheProperties;
import cn.wzw.llm.study.llmstudy.dto.locate.LocateResultItem;
import cn.wzw.llm.study.llmstudy.dto.retrieval.GenerationReferenceBundle;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 检索结果本地缓存服务。
 * 针对相同的 query + directiveFilename + 检索参数组合，在 TTL 内直接返回缓存结果，
 * 避免重复调用向量检索、ES 和 Rerank API。
 * <p>
 * 缓存值存入/读出时都做不可变快照，避免外部修改污染缓存。
 */
@Service
@Slf4j
public class RetrievalCacheService {

    private final Cache<String, GenerationReferenceBundle> retrievalCache;
    private final Cache<String, List<LocateResultItem>> locateCache;

    public RetrievalCacheService(CacheProperties cacheProperties) {
        this.retrievalCache = Caffeine.newBuilder()
                .expireAfterWrite(Math.max(1, cacheProperties.getRetrievalTtlSeconds()), TimeUnit.SECONDS)
                .maximumSize(Math.max(1, cacheProperties.getRetrievalMaxSize()))
                .build();
        this.locateCache = Caffeine.newBuilder()
                .expireAfterWrite(Math.max(1, cacheProperties.getLocateTtlSeconds()), TimeUnit.SECONDS)
                .maximumSize(Math.max(1, cacheProperties.getLocateMaxSize()))
                .build();
    }

    public GenerationReferenceBundle getRetrieval(String key) {
        GenerationReferenceBundle value = retrievalCache.getIfPresent(key);
        if (value != null) {
            log.debug("检索缓存命中 key={}", key);
        }
        return value;
    }

    public void putRetrieval(String key, GenerationReferenceBundle value) {
        if (value == null) {
            return;
        }
        // 深拷贝成不可变快照：List.copyOf 防止外部对 bundle 的 contents / references 做 add/remove
        GenerationReferenceBundle snapshot = new GenerationReferenceBundle(
                List.copyOf(value.contents()),
                List.copyOf(value.referenceMaterials())
        );
        retrievalCache.put(key, snapshot);
    }

    public List<LocateResultItem> getLocate(String key) {
        List<LocateResultItem> value = locateCache.getIfPresent(key);
        if (value != null) {
            log.debug("定位缓存命中 key={}", key);
        }
        return value;
    }

    public void putLocate(String key, List<LocateResultItem> value) {
        if (value == null) {
            return;
        }
        locateCache.put(key, List.copyOf(value));
    }

    /**
     * 基于请求参数生成缓存 key：sha256(query|filename|topK|threshold|...)。
     */
    public static String buildRetrievalKey(String query, String directiveFilename, int vectorTopK,
                                           int keywordTopK, double threshold, int finalTopK) {
        String raw = String.join("|",
                normalizeQuery(query),
                directiveFilename == null ? "" : directiveFilename.trim(),
                String.valueOf(vectorTopK),
                String.valueOf(keywordTopK),
                String.valueOf(threshold),
                String.valueOf(finalTopK));
        return sha256hex(raw);
    }

    /** 文件定位专用 key */
    public static String buildLocateKey(String query) {
        return sha256hex("locate|" + normalizeQuery(query));
    }

    /**
     * 查询归一化：trim + 合并连续空白 + 小写化（CJK 不受影响），提升缓存命中率。
     */
    private static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    private static String sha256hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }
}
