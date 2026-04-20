# llmstudy

基于 `Java 21`、`Spring Boot 3.5`、`Spring AI` 和 `LangChain4j` 的大模型学习项目，按能力拆成多个独立模块，方便分别验证基础对话、函数调用和 RAG 方案。

## 模块说明

- `springai`: Spring AI 基础对话、对话记忆与 JDBC 持久化示例
- `langchain4j`: LangChain4j 对话、流式输出、Redis 记忆与工具集成示例
- `functioncall`: Spring AI 函数调用示例
- `rag`: 基础 RAG，包含文档解析、向量化与检索能力
- `pro-rag`: 增强版 RAG，包含文件上传、混合检索和 rerank 配置

## 环境要求

- `JDK 21`
- `Maven 3.9+`
- `MySQL`（`springai` 模块）
- `Redis`（`langchain4j` 模块）
- `PostgreSQL + pgvector`（`rag` / `pro-rag` 模块）
- `Elasticsearch`（`rag` / `pro-rag` 模块）

## 默认端口

各模块端口已调整为默认不冲突，可分别启动：

- `springai`: `28688`
- `langchain4j`: `28689`
- `functioncall`: `28690`
- `rag`: `28691`
- `pro-rag`: `28692`

如需覆盖，启动时传入 `SERVER_PORT` 即可。

## 环境变量

项目已将 API Key 和数据库连接改为优先从环境变量读取。

### 通用

- `ZHIPU_API_KEY`: 智谱 API Key
- `ZHIPU_BASE_URL`: 智谱兼容 OpenAI 接口地址，默认 `https://open.bigmodel.cn/api/paas/v4`
- `SERVER_PORT`: 覆盖当前模块端口

### `springai`

- `MYSQL_URL`
- `MYSQL_USERNAME`
- `MYSQL_PASSWORD`
- `SPRINGAI_CHAT_MODEL`

### `langchain4j`

- `REDIS_HOST`
- `REDIS_PORT`
- `LANGCHAIN4J_CHAT_MODEL`
- `LANGCHAIN4J_STREAMING_CHAT_MODEL`

### `functioncall`

- `FUNCTIONCALL_CHAT_MODEL`

### `rag`

- `PG_URL`
- `PG_USERNAME`
- `PG_PASSWORD`
- `ELASTICSEARCH_URIS`
- `RAG_CHAT_MODEL`
- `RAG_EMBEDDING_MODEL`
- `RAG_EMBEDDING_DIMENSIONS`

### `pro-rag`

- `PG_URL`
- `PG_USERNAME`
- `PG_PASSWORD`
- `ELASTICSEARCH_URIS`
- `PRO_RAG_CHAT_MODEL`
- `PRO_RAG_EMBEDDING_MODEL`
- `PRO_RAG_EMBEDDING_DIMENSIONS`
- `PRO_RAG_UPLOAD_DIR`
- `PRO_RAG_GENERATED_DIR`
- `PRO_RAG_RERANK_ENABLED`

## 快速启动

先设置必要环境变量，例如：

```bash
export ZHIPU_API_KEY=your_key
export MYSQL_PASSWORD=your_mysql_password
export PG_PASSWORD=your_pg_password
```

按模块启动：

```bash
mvn -pl springai spring-boot:run
mvn -pl langchain4j spring-boot:run
mvn -pl functioncall spring-boot:run
mvn -pl rag spring-boot:run
mvn -pl pro-rag spring-boot:run
```

如果需要一次性构建全部模块：

```bash
mvn clean package
```

## 版本管理

依赖版本统一在根 `pom.xml` 管理：

- `Spring AI BOM`
- `Spring AI Alibaba BOM`
- `LangChain4j` 相关依赖
- `fastjson2`
- `commons-compress`
- `elasticsearch-java`
- `mysql-connector-java`

新增公共依赖版本时，优先放到根 `pom.xml` 的 `properties` 或 `dependencyManagement` 中，避免子模块重复声明。
