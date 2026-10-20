# 智能面试辅助平台

> 基于 Spring Boot + Spring AI + React 的一站式 AI 面试准备平台。

## 项目简介

智能面试辅助平台面向求职者，覆盖从简历优化到模拟面试的完整流程：

- 上传简历，由 AI 解析并给出结构化信息与多维度评分；
- 选择技术方向、难度和题目数量，进行文字模拟面试，获得逐题评分与面试报告；
- 进行实时语音面试，支持语音识别（ASR）、语音合成（TTS）和实时字幕；
- 上传面经、笔记等资料搭建个人知识库，通过 RAG 进行语义检索和 AI 问答；
- 用日历管理面试安排，AI 自动解析面试邀约文本；
- 在设置页运行时切换不同的 LLM Provider。

## 技术栈

### 后端

| 技术 | 说明 |
|------|------|
| Java 21 | 使用虚拟线程 |
| Spring Boot 4.0.6 | Web MVC、Validation、Data JPA、WebSocket |
| Spring AI 2.0.0-M4 | ChatClient、PgVector Store、Embedding |
| PostgreSQL 16 + pgvector | 业务数据与向量数据存储 |
| Redis 7 + Redisson 4.0 | 会话缓存、Redis Stream 异步任务队列 |
| MinIO（S3 协议） | 简历与知识库文件存储 |
| Apache Tika 2.9 | PDF / DOC / DOCX / TXT / Markdown 文档解析 |
| iText 8 | 简历分析报告 PDF 导出 |
| SpringDoc OpenAPI 2.8 | Swagger 接口文档 |
| Lombok / MapStruct | 简化样板代码与对象映射 |

### 前端

| 技术 | 说明 |
|------|------|
| React 18 + TypeScript 5.6 | 页面开发 |
| Vite 5 | 开发服务器与构建 |
| Tailwind CSS 4 | 样式方案 |
| React Router 7 | 路由 |
| Axios | HTTP 请求 |
| Recharts | 评分雷达图 |
| react-big-calendar | 面试日程日历 |
| react-markdown / react-syntax-highlighter | Markdown 与代码块渲染 |
| framer-motion | 动画 |
| onnxruntime-web + VAD | 浏览器端语音活动检测 |
| pnpm 10 | 包管理 |

### AI 能力

默认通过阿里云百炼（DashScope）的 OpenAI 兼容接口调用 Qwen 系列模型，同时支持：

- 运行时新增 / 修改 / 删除 LLM Provider，并切换默认对话与 Embedding Provider；
- 语音面试使用 DashScope 的 ASR / TTS 服务；
- 提示词模板统一放在 `src/main/resources/prompts/` 下。

## 功能模块

| 模块 | 说明 |
|------|------|
| 简历管理 | 简历上传、AI 解析、评分展示、重新分析、PDF 导出 |
| 文字面试 | 按技术方向与难度出题、作答、AI 评估报告、历史记录 |
| 语音面试 | WebSocket 实时音频、ASR / TTS、暂停恢复、异步评估 |
| 知识库 | 文档上传、异步向量化、分类管理、RAG 问答与多轮对话 |
| 面试日程 | 邀约文本解析、日程 CRUD、日历视图、状态管理 |
| LLM Provider | 多模型配置、连通性测试、默认模型切换、ASR / TTS 配置 |

### 简历管理

- 支持 PDF、DOC、DOCX、TXT、Markdown，单文件最大 20 MB；
- 使用 Tika 提取正文，再由 LLM 解析出姓名、联系方式、技能、工作经历、教育背景等结构化信息；
- 前端以雷达图展示内容、结构、技能匹配、表达、项目经验等维度得分；
- 支持重新分析、删除，以及将分析结果导出为 PDF。

### 文字模拟面试

- 内置 Java 后端、前端开发、数据科学 / AI、DevOps / SRE、Go 开发、Python 后端 6 个预设方向及其分类；
- 根据简历、方向、难度（junior / mid / senior）和题目数量由 AI 生成面试题；
- 支持提交答案、暂存答案、查询当前题目、提前结束面试；
- 面试完成后由 AI 生成报告：总分、分类得分、逐题评分与反馈、优势、改进建议、参考答案；
- 支持面试历史查询与报告 PDF 导出；
- 支持粘贴 JD，由 AI 解析出技术分类。

### 语音模拟面试

- 前端采集麦克风音频（PCM）并通过 WebSocket 发送，后端实时返回字幕与 TTS 音频分片；
- WebSocket 地址：`/ws/voice-interview/{sessionId}`；
- 支持面试中暂停 / 恢复，并自动扣除暂停时长；
- 会话状态流转：`CREATED → IN_PROGRESS ⇄ PAUSED → COMPLETED → EVALUATED`；
- 面试结束后通过 Redis Stream 异步执行 AI 评估，前端轮询评估状态并展示报告。

### 知识库 / RAG

- 支持 PDF、DOC、DOCX、TXT、Markdown 等文档，按文件 SHA-256 去重；
- 上传后通过 Redis Stream 异步完成文本分块（TokenTextSplitter，约 800 tokens/块）、Embedding 与 pgvector 入库，并记录 `PENDING / PROCESSING / COMPLETED / FAILED` 状态；
- 支持分类、搜索、统计、下载、删除与失败重试（重新向量化）；
- 问答链路包含 Query Rewrite、按问题长度动态调整 topK / minScore、多知识库检索和 Prompt 拼装，支持同步与 SSE 流式回答；
- RAG 会话支持历史记录、重命名、置顶和切换关联知识库。

### 面试日程

- 粘贴面试邀约文本，采用「规则优先、AI 降级」策略解析出公司、职位、时间、链接等信息；
- 面试日程的增删改查、状态变更与日历视图展示。

### LLM Provider

- 在设置页管理多个 LLM Provider（Base URL、API Key、模型名等），支持连通性测试；
- 可分别设置默认对话模型、默认 Embedding 模型，以及语音面试使用的 ASR / TTS 模型；
- 配置持久化到数据库，修改后可热重载，无需重启服务。

## 项目结构

```
.
├── src/main/java/interview/guide/
│   ├── common/            # 通用基础：Result、异常、AI 结构化输出、异步框架、配置
│   ├── infrastructure/    # 基础设施：Redis、文件解析与存储、PDF 导出
│   └── modules/           # 业务模块
│       ├── resume/            # 简历管理
│       ├── interview/         # 文字模拟面试
│       ├── voice/             # 语音面试（WebSocket / ASR / TTS）
│       ├── knowledgebase/     # 知识库与 RAG 问答
│       ├── interviewschedule/ # 面试日程
│       └── llmprovider/       # LLM Provider 配置
├── src/main/resources/
│   ├── application.yml        # 主配置
│   └── prompts/               # LLM 提示词模板
├── frontend/                  # React + TypeScript 前端
├── docker/postgres/init.sql   # 启用 pgvector 扩展
└── docker-compose.dev.yml     # 本地开发依赖：PostgreSQL / Redis / MinIO
```

## 快速开始

### 环境要求

- JDK 21
- Node.js 20+、pnpm 10+
- Docker 与 Docker Compose
- LLM API Key

### 1. 启动依赖服务

```bash
docker compose -f docker-compose.dev.yml up -d
```

该命令会启动：

| 服务 | 地址 | 默认账号 |
|------|------|----------|
| PostgreSQL（含 pgvector） | `localhost:5432`，数据库 `interview_learn` | `postgres / 123456` |
| Redis | `localhost:6379` | 无 |
| MinIO API | `http://localhost:9000` | `minioadmin / minioadmin` |
| MinIO 控制台 | `http://localhost:9001` | `minioadmin / minioadmin` |

`createbuckets` 容器会自动创建并公开 `interview-guide` 存储桶。

### 2. 配置环境变量

```bash
cp .env.example .env
```

至少填写 DashScope API Key：

```dotenv
AI_BAILIAN_API_KEY=your_dashscope_api_key_here
```

其他常用配置：

```dotenv
AI_MODEL=qwen3.5-flash

POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=interview_learn
POSTGRES_USER=postgres
POSTGRES_PASSWORD=123456

REDIS_HOST=localhost
REDIS_PORT=6379

APP_STORAGE_ENDPOINT=http://localhost:9000
APP_STORAGE_ACCESS_KEY=minioadmin
APP_STORAGE_SECRET_KEY=minioadmin
APP_STORAGE_BUCKET=interview-guide
```

> **注意**：`APP_STORAGE_ACCESS_KEY / APP_STORAGE_SECRET_KEY` 需要与 `docker-compose.dev.yml` 中的 MinIO 账号一致，默认为 `minioadmin / minioadmin`。

### 3. 启动后端

```bash
./gradlew bootRun
```

`bootRun` 会自动读取根目录的 `.env` 并注入环境变量。启动后：

- 后端地址：`http://localhost:8080`
- Swagger UI：`http://localhost:8080/swagger-ui.html`

数据库表由 JPA `ddl-auto: update` 自动创建，pgvector 扩展由 `docker/postgres/init.sql` 在容器首次启动时启用。

### 4. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

访问 `http://localhost:5173`。开发服务器已将 `/api` 代理到 `http://localhost:8080`。

### 5. 构建

```bash
# 后端
./gradlew build

# 前端
cd frontend
pnpm build
```

前端目录提供了 `Dockerfile` 与 `nginx.conf`，可用于构建静态资源镜像。

## 主要接口

所有业务接口均以 `/api` 为前缀，常用入口：

| 模块 | 路径前缀 |
|------|----------|
| 简历 | `/api/resumes` |
| 文字面试 | `/api/interview` |
| 语音面试 | `/api/voice-interview`、WebSocket `/ws/voice-interview/{sessionId}` |
| 知识库 | `/api/knowledgebase` |
| RAG 对话 | `/api/rag-chat` |
| 面试日程 | `/api/interview-schedule` |
| LLM Provider | `/api/llm-provider` |

完整接口说明见 Swagger UI：`http://localhost:8080/swagger-ui.html`

## 配置说明

| 环境变量 | 说明 | 默认值 |
|----------|------|--------|
| `AI_BAILIAN_API_KEY` | DashScope API Key（必填） | 无 |
| `AI_MODEL` | 默认对话模型 | `qwen3.7-max` |
| `POSTGRES_HOST` / `POSTGRES_PORT` | PostgreSQL 地址 | `localhost` / `5432` |
| `POSTGRES_DB` | 数据库名 | `interview_learn` |
| `REDIS_HOST` / `REDIS_PORT` | Redis 地址 | `localhost` / `6379` |
| `APP_STORAGE_ENDPOINT` | S3 兼容存储地址 | `http://localhost:9000` |
| `APP_STORAGE_BUCKET` | 存储桶 | `interview-guide` |
| `APP_INTERVIEW_EVALUATION_BATCH_SIZE` | AI 评估时的分批大小 | `8` |

## 注意事项

- 项目目前按开发环境配置：CORS 仅放行 `http://localhost:5173`，WebSocket 允许所有来源，生产环境需要收紧。
- LLM Provider 的 API Key 以 Base64 编码存储，仅用于开发演示；生产环境应改用 AES-GCM 等加密方案。
- 文件上传大小受 `spring.servlet.multipart.max-file-size`（20 MB）限制，知识库服务自身校验上限为 50 MB。
- 知识库向量化与语音面试评估均为 Redis Stream 异步任务，需保证 Redis 可用。

## License

本项目基于 [MIT License](LICENSE) 开源。
