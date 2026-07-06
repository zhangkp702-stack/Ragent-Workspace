# Ragent

Ragent 是一个面向企业知识库场景的 RAG 智能问答系统，提供知识库管理、文档解析、向量检索、多轮对话、流式问答、意图识别、模型路由以及后台管理能力。项目采用前后端分离架构，后端基于 Spring Boot 构建，前端基于 React + Vite 构建。

## 功能特性

- 智能问答：支持基于知识库内容的 RAG 问答，使用 SSE 进行流式输出。
- 多轮对话：支持会话管理、消息记录、历史记忆和摘要压缩。
- 知识库管理：支持知识库、文档、分片、向量化状态等管理能力。
- 文档处理：支持文件解析、文本切分、增强处理和向量入库。
- 多通道检索：支持意图定向检索、全局向量检索、结果去重和重排。
- 意图识别：支持问题改写、问题拆分、意图分类和歧义引导。
- 模型接入：支持聊天模型、Embedding 模型、Rerank 模型的路由与调用。
- 后台管理：提供模型配置、知识库、检索链路、系统设置等管理页面。
- MCP 扩展：包含独立 MCP Server 模块，可扩展外部工具调用能力。

## 技术栈

### 后端

- Java 17
- Spring Boot 3.5.x
- Maven 多模块工程
- MyBatis-Plus
- PostgreSQL / pgvector
- Milvus
- Redis / Redisson
- RocketMQ
- Sa-Token
- Apache Tika
- OkHttp
- AWS S3 SDK

### 前端

- React 18
- TypeScript
- Vite
- Tailwind CSS
- React Router
- Zustand
- Axios
- Radix UI
- Recharts
- React Markdown

## 模块结构

```text
ragent
├── bootstrap      # 应用启动模块，包含 Controller、Service、RAG 主链路等
├── framework      # 通用框架能力，如上下文、异常、ID、Web、MQ 等
├── infra-ai       # AI 模型接入层，包含 Chat、Embedding、Rerank 等能力
├── mcp-server     # MCP 服务模块
├── frontend       # 前端管理端与问答页面
├── resources      # 数据库脚本、Docker 编排、示例知识文档等资源
├── docs           # 项目文档
└── assets         # README 或文档使用的图片资源
```

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 18+
- PostgreSQL，建议启用 pgvector 扩展
- Milvus，可使用 `resources/docker` 下的 compose 文件启动
- Redis
- RocketMQ

### 初始化数据库

PostgreSQL 初始化脚本位于：

```text
resources/database/schema_pg.sql
resources/database/init_data_pg.sql
```

如果是已有数据库升级，可参考：

```text
resources/database/upgrade_v1.0_to_v1.1.sql
resources/database/upgrade_v1.1_to_v1.2.sql
resources/database/upgrade_v1.2_to_v1.3.sql
```

### 启动后端

```bash
./mvnw -pl bootstrap -am spring-boot:run
```

Windows 环境可使用：

```powershell
.\mvnw.cmd -pl bootstrap -am spring-boot:run
```

### 启动前端

```bash
cd frontend
npm install
npm run dev
```

## 常用接口

- `GET /rag/v3/chat`：发起 SSE 流式问答。
- `POST /rag/v3/stop`：停止指定流式生成任务。
- `/conversations`：会话列表、重命名、删除和消息查询。
- `/knowledge`：知识库和文档相关管理接口。

## 开发说明

- 后端主入口：`bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java`
- RAG 流式问答入口：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java`
- RAG 主编排链路：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java`
- 前端入口：`frontend/src`

## License

本项目使用 Apache License 2.0。
