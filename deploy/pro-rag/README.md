# Pro-RAG 部署说明

这套文件面向单机部署：

- `nginx`：对外提供入口，并做 Basic Auth
- `app`：`pro-rag` Spring Boot 服务
- `postgres`：`pgvector` 向量库
- `elasticsearch`：关键词检索

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

至少改这两个值：

- `ZHIPU_API_KEY`
- `PG_PASSWORD`

## 3. 生成 Basic Auth 账号密码

先创建放密码文件的目录：

```bash
mkdir -p nginx data/uploads data/generated
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
docker compose logs -f elasticsearch
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

仅重启应用：

```bash
docker compose restart app
```

## 7. 上线前建议

这套配置可以先直接跑起来，但正式长期使用前建议再补两件事：

1. 给域名配 HTTPS
2. 把安全组只保留 `22`、`80`、`443`
