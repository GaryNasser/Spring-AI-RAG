# Spring-AI-RAG

基于 Spring Boot 3 + Spring AI 的 Markdown 知识库问答服务（"how-to-cook"），
参考 [all-in-rag](https://github.com/datawhalechina/all-in-rag) 实现。

## 运行前准备

JWT 签名密钥不再硬编码在源码中，需通过环境变量提供（HS256，Base64 可解码且解码后不少于 32 字节）：

```bash
cp .env.example .env   # 填入 JWT_SECRET_KEY
export JWT_SECRET_KEY=<你的密钥>
```

其余本机依赖（PostgreSQL / MinIO）在 `application.properties` 中已给出默认值并支持覆盖：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `JWT_SECRET_KEY` | 无（必填） | JWT 签名密钥 |
| `DB_PASSWORD` | `rag_password` | PostgreSQL 密码 |
| `MINIO_ACCESS_KEY` | `minioadmin` | MinIO 访问密钥 |
| `MINIO_SECRET_KEY` | `minioadmin` | MinIO 私钥 |

依赖服务（Elasticsearch、Kibana、MinIO、PostgreSQL）可由 `compose.yaml` 一键拉起：

```bash
docker compose up -d
./mvnw spring-boot:run
```

## 已知问题

本项目为课程作业，实现上仍有不足。
