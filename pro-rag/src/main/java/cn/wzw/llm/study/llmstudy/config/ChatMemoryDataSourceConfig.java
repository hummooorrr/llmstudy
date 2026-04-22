package cn.wzw.llm.study.llmstudy.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 为"对话记忆/会话元信息"单独配置一套 MySQL 数据源。
 * 主 DataSource 保持给 pgvector 使用，不动它。
 * <p>
 * 启动时会自动建表（如果 {@code pro-rag.chat-memory.initialize-schema=true}）：
 * <ul>
 *   <li>{@code pro_rag_chat_message} - 持久化每条消息</li>
 *   <li>{@code pro_rag_conversation} - 会话元信息（标题/领域/更新时间）</li>
 * </ul>
 */
@Configuration
@Slf4j
public class ChatMemoryDataSourceConfig {

    @Value("${pro-rag.chat-memory.initialize-schema:true}")
    private boolean initializeSchema;

    /**
     * 一旦项目显式声明了任意 DataSource，Spring Boot 的默认 DataSource 自动配置就会回退。
     * 因此这里把主库（PostgreSQL）也显式声明出来，供 pgvector / Embedding 相关组件使用。
     */
    @Bean(name = "dataSourceProperties")
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource(
            @Qualifier("dataSourceProperties") DataSourceProperties props) {
        HikariDataSource ds = props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("PrimaryPgHikari");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30_000);
        return ds;
    }

    @Bean
    @ConfigurationProperties("pro-rag.chat-memory.datasource")
    public DataSourceProperties chatMemoryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "chatMemoryDataSource")
    public DataSource chatMemoryDataSource(
            @Qualifier("chatMemoryDataSourceProperties") DataSourceProperties props) {
        HikariDataSource ds = props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("ChatMemoryHikari");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30_000);
        return ds;
    }

    @Bean(name = "chatMemoryJdbcTemplate")
    public JdbcTemplate chatMemoryJdbcTemplate(
            @Qualifier("chatMemoryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 显式声明一个 @Primary 的 JdbcTemplate 绑到主 DataSource（PostgreSQL）。
     * <p>
     * 为什么需要这个 bean？
     * <ul>
     *     <li>项目里新增的 {@link #chatMemoryJdbcTemplate} 会让
     *         {@code JdbcTemplateAutoConfiguration} 的 {@code @ConditionalOnMissingBean(JdbcOperations.class)}
     *         条件变为 false，从而不再自动创建默认的 jdbcTemplate。</li>
     *     <li>此时 {@code @Autowired JdbcTemplate}（例如 {@code EmbeddingService}）会唯一
     *         注入到 MySQL 的 chatMemoryJdbcTemplate，但这些调用方实际需要的是 PG 库。</li>
     * </ul>
     * 这里显式以 {@code @Primary} 暴露一个基于主 DataSource 的 JdbcTemplate，保证老代码行为不变。
     */
    @Bean(name = "jdbcTemplate")
    @Primary
    public JdbcTemplate primaryJdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void init() {
        if (!initializeSchema) {
            log.info("pro-rag.chat-memory.initialize-schema=false，跳过自动建表");
        }
    }
}
