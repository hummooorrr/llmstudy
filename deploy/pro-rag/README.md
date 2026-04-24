# Pro-RAG 部署说明

这套文件面向单机部署：

- `nginx`：对外提供入口，并做 Basic Auth
- `app`：`pro-rag` Spring Boot 服务
- `postgres`：`pgvector` 向量库
- `elasticsearch`：关键词检索
- `mysql`：对话记忆 / 会话元信息持久化（RAG 对话和文档生成的多轮历史全部落库，重启、刷新都不丢）

## 1. 上传到服务器

把整个仓库传到服务器后，进入部署目录：

```bash
cd /opt/llmstudy/deploy/pro-rag
```

## 2. 准备环境变量

```bash
cp .env.example .env
vim .env
```

至少改这些值：

- `ZHIPU_API_KEY`
- `PG_PASSWORD`
- `MYSQL_ROOT_PASSWORD`
- `MYSQL_PASSWORD`

常用模型相关配置也建议一并确认：

- `PRO_RAG_CHAT_MODEL`：问答 / 改写使用的聊天模型，默认 `glm-5.1`
- `PRO_RAG_EMBEDDING_MODEL`：向量化使用的 embedding 模型，默认 `embedding-3`
- `PRO_RAG_VISION_MODEL`：图片 / 扫描 PDF 识别使用的视觉模型，默认 `glm-4.6v`
- `PRO_RAG_RERANK_MODEL`：检索结果精排使用的 rerank 模型，默认 `rerank`

## 3. 生成 Basic Auth 账号密码

先创建放密码文件的目录：

```bash
mkdir -p nginx data/uploads data/generated
```

如果之前误创建过 `nginx/.htpasswd` 目录，先删掉：

```bash
rm -rf nginx/.htpasswd
```

生成账号密码文件，下面示例账号是 `mom`：

```bash
docker run --rm httpd:2.4-alpine htpasswd -Bbn mom "换成你的强密码" > nginx/.htpasswd
```

以后如果想新增一个账号：

```bash
docker run --rm httpd:2.4-alpine htpasswd -Bbn another_user "换成另一个密码" >> nginx/.htpasswd
```

更简单的做法是直接重新生成一个新的 `nginx/.htpasswd` 文件覆盖旧文件。

## 4. 首次启动

`docker-compose.yml` 里的 `elasticsearch` 已改成**构建自定义镜像**，启动时会自动安装 `analysis-ik` 中文分词插件，所以首次构建会比普通镜像拉起更久一点，属于正常现象。

```bash
docker compose up -d --build
```

查看容器状态：

```bash
docker compose ps
```

查看日志：

```bash
docker compose logs -f app
docker compose logs -f nginx
docker compose logs -f postgres
docker compose logs -f mysql
docker compose logs -f elasticsearch
```

如果你改过 `nginx.conf` 或 `.htpasswd`，重启 `nginx` 即可：

```bash
docker compose restart nginx
```

## 5. 访问方式

浏览器打开：

```text
http://你的服务器公网IP/
```

会先弹出 Basic Auth 登录框，输入你刚刚生成的账号密码即可。

## 6. 常用命令

停止：

```bash
docker compose down
```

重新构建并启动：

```bash
docker compose up -d --build
```

如果你之前已经拉起过旧版 `elasticsearch`（未安装 IK 插件），升级到当前部署文件后也要执行一次带 `--build` 的重建，确保 ES 容器换成新镜像。

仅重启应用：

```bash
docker compose restart app
```

## 7. 升级已有环境（仅限结构化解析版本）

如果你是从"旧的只做纯文本切分"的老版本升级到当前结构化解析版本，**ES 索引里新增了几个 metadata 字段**（`chunkType` / `pageNumber` / `sectionPath` / `assetPath` / `chunkProfile` 等）。应用启动时会自动尝试 `put-mapping` 做增量追加，正常情况下无需任何操作。

只有下面两种情况才需要手动介入：

- 启动日志出现 `ES metadata mapping 追加失败（可能是字段类型冲突，建议新环境 reindex）`
- 或者你希望前端的"引用预览 / 同页邻居"功能完整生效

那就走一遍重建流程（会清空所有 ES 索引，PG 向量库不动）：

```bash
# 容器已在跑的前提下，在宿主机执行（把 USER:PASS 换成你 nginx basic auth 的账密）
curl -u USER:PASS -X POST http://你的服务器公网IP/pro-rag/admin/recreate-index
```

之后进入前端页面，对每个之前上传过的文件逐个点"补录"，或者直接调 API：

```bash
curl -u USER:PASS -X POST \
  "http://你的服务器公网IP/pro-rag/reingest?filename=xxx.pdf"
```

扫描件或特殊文档可以带 profile 参数强制走某条流水线，比如：

```bash
curl -u USER:PASS -X POST \
  "http://你的服务器公网IP/pro-rag/reingest?filename=xxx.pdf&profile=pdf-scanned"
```

## 8. 成本开关

`.env` 里的两个结构化解析开关用于平衡"检索质量"和"VL 调用成本"：

- `PRO_RAG_STRUCTURED_PARSE_ENABLED=true`：PDF/DOCX 会额外抽取表格 → Markdown、图片 → VL 描述，带来每份文档数次到十几次视觉模型调用。关掉后走纯文本切分流程，成本最低。
- `PRO_RAG_MAX_IMAGES_PER_DOC=20`：单份文档抽取图片数上限。像目录图、水印图、装饰图多的 PDF 可以调小（比如 5）限制 VL 费用。

修改 `.env` 后只需 `docker compose up -d app` 生效。

## 9. 对话记忆相关运维

对话记忆全部落在 MySQL 两张表：

- `pro_rag_chat_message`：每条 user / assistant 消息
- `pro_rag_conversation`：会话元信息（scope、title、message_count、updated_at）

清空历史：

```bash
# 进入 mysql 容器
docker compose exec mysql mysql -u root -p"$MYSQL_ROOT_PASSWORD" pro_rag

# 只清空"文档生成"作用域的历史
DELETE m FROM pro_rag_chat_message m
  JOIN pro_rag_conversation c ON c.conversation_id = m.conversation_id
  WHERE c.scope = 'GENERATE';
DELETE FROM pro_rag_conversation WHERE scope = 'GENERATE';

# 全部清空
TRUNCATE TABLE pro_rag_chat_message;
TRUNCATE TABLE pro_rag_conversation;
```

备份：

```bash
docker compose exec mysql mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" pro_rag \
  > pro_rag_memory_$(date +%Y%m%d).sql
```

## 10. 上线前建议

这套配置可以先直接跑起来，但正式长期使用前建议再补两件事：

1. 给域名配 HTTPS
2. 把安全组只保留 `22`、`80`、`443`
