# Frontend API 文档

前端期望的后端 API 接口定义，按模块分类。

## 通用约定

- **请求方式**: RESTful
- **响应格式**: 统一包 `{code: number, message: string, data: T}`
  - `code === 200` → 成功，前端取 `data`
  - `code !== 200` → 失败，前端取 `message` 显示
- **Content-Type**: JSON (`application/json`)，上传用 `multipart/form-data`
- **Base URL**: 开发环境 `http://localhost:8080`

---

## 1. 简历上传

### POST /api/resumes/upload

上传简历文件，后端完成 Tika 解析 + AI 分析。

- **Content-Type**: `multipart/form-data`
- **Body**: `file` — PDF/DOCX/DOC/TXT 文件
- **超时**: 5 分钟

**返回示例**:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "storage": {
      "fileKey": "resumes/2026/06/21/a1b2c3d4_简历.pdf",
      "fileUrl": "http://localhost:9000/interview-guide/resumes/2026/06/21/a1b2c3d4_简历.pdf",
      "resumeId": 7
    },
    "analysis": null,
    "duplicate": false
  }
}
```

**字段说明**:

| 路径 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `storage.fileKey` | string | ✅ | MinIO 存储键 |
| `storage.fileUrl` | string | ✅ | 文件访问 URL |
| `storage.resumeId` | number | ✅ | 简历 ID |
| `analysis` | object | ❌ | AI 分析结果（异步可能为 null） |
| `duplicate` | boolean | ❌ | 是否重复文件 |

### GET /api/resumes/health

健康检查，返回 `{status: "ok", service: "resume"}`。

---

## 2. 简历历史

### GET /api/resumes

获取全部简历列表。

**返回**: `ResumeListItem[]`

```typescript
interface ResumeListItem {
  id: number;
  filename: string;
  fileSize: number;
  uploadedAt: string;       // ISO 时间
  accessCount: number;
  latestScore?: number;
  lastAnalyzedAt?: string;
  interviewCount: number;
  analyzeStatus?: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  analyzeError?: string;
  storageUrl?: string;
}
```

### GET /api/resumes/{id}/detail

获取简历详情（含分析记录和面试记录）。

**返回**: `ResumeDetail`

```typescript
interface ResumeDetail {
  id: number;
  filename: string;
  fileSize: number;
  contentType: string;
  storageUrl: string;
  uploadedAt: string;
  resumeText: string;           // 原始文本
  analyzeStatus?: string;
  analyzeError?: string;
  analyses: AnalysisItem[];     // 分析记录
  interviews: InterviewItem[];  // 面试记录
}

interface AnalysisItem {
  id: number;
  overallScore: number;         // 总分 0-100
  contentScore: number;         // 内容完整性 0-25
  structureScore: number;       // 结构清晰度 0-20
  skillMatchScore: number;      // 技能匹配度 0-25
  expressionScore: number;      // 表达专业性 0-15
  projectScore: number;         // 项目经验 0-15
  summary: string;
  analyzedAt: string;
  strengths: string[];
  suggestions: Suggestion[];
}
```

### GET /api/resumes/statistics

获取统计信息。

**返回**: `{totalCount: number, totalInterviewCount: number, totalAccessCount: number}`

### POST /api/resumes/{id}/reanalyze

重新分析简历，无请求体，返回 `void`。

### DELETE /api/resumes/{id}

删除简历，返回 `void`。

### GET /api/resumes/{resumeId}/export

导出简历分析报告 PDF，返回 `Blob`。

---

## 3. 模拟面试

### GET /api/interview/sessions

获取所有面试会话列表。

**返回**: `TextSessionMeta[]`

### POST /api/interview/sessions

创建面试会话。

**Body**:
```json
{
  "resumeId": 1,
  "questionCount": 5,
  "categories": ["技术", "项目"]
}
```
**超时**: 3 分钟

**返回**: `InterviewSession`

### GET /api/interview/sessions/{sessionId}

获取面试会话详情。

### GET /api/interview/sessions/{sessionId}/question

获取当前面试题目。

**返回**: `CurrentQuestionResponse`

### POST /api/interview/sessions/{sessionId}/answers

提交答案。

**Body**:
```json
{
  "sessionId": "...",
  "questionIndex": 0,
  "answer": "我的回答..."
}
```
**超时**: 3 分钟

### PUT /api/interview/sessions/{sessionId}/answers

修改答案。

### GET /api/interview/sessions/{sessionId}/report

获取面试评估报告，**超时**: 3 分钟。

### GET /api/interview/sessions/unfinished/{resumeId}

获取某简历的未完成面试。

### POST /api/interview/sessions/{sessionId}/complete

完成面试。

### GET /api/interview/sessions/{sessionId}/details

获取面试详情（含答案和评估）。

### GET /api/interview/sessions/{sessionId}/export

导出面试报告 PDF，返回 `Blob`。

### DELETE /api/interview/sessions/{sessionId}

删除面试记录。

---

## 4. 技能

### GET /api/interview/skills

获取所有技能列表。

### GET /api/interview/skills/{id}

获取单个技能详情。

### POST /api/interview/skills/parse-jd

解析 JD 文本提取技能。

**Body**: `{jdText: string}`

---

## 5. 知识库

### POST /api/knowledgebase/upload

上传知识库文件，`multipart/form-data`。5 分钟超时。

### GET /api/knowledgebase/list?keyword=&category=&page=&size=

获取知识库列表。

### GET /api/knowledgebase/{id}

获取单个知识库详情。

### DELETE /api/knowledgebase/{id}

删除知识库。

### GET /api/knowledgebase/{id}/download

下载知识库文件。

### GET /api/knowledgebase/categories

获取所有分类。

### GET /api/knowledgebase/category/{category}

按分类查询。

### GET /api/knowledgebase/uncategorized

获取未分类条目。

### PUT /api/knowledgebase/{id}/category

更新分类。**Body**: `{category: string}`

### GET /api/knowledgebase/search?keyword=

关键词搜索。

### GET /api/knowledgebase/stats

获取统计信息。

### POST /api/knowledgebase/{id}/revectorize

重新向量化。

### POST /api/knowledgebase/query

RAG 查询，3 分钟超时。

### /api/knowledgebase/query/stream (SSE)

流式 RAG 查询，通过 `EventSource` 或 `fetch` 消费。

---

## 6. RAG 对话

### GET /api/rag-chat/sessions

获取会话列表。

### POST /api/rag-chat/sessions

创建对话会话。

**Body**:
```json
{
  "resumeId": 1,
  "title": "面试准备咨询",
  "knowledgeBaseIds": [1, 2]
}
```

### GET /api/rag-chat/sessions/{sessionId}

获取会话详情。

### PUT /api/rag-chat/sessions/{sessionId}/title

修改标题。**Body**: `{title: string}`

### PUT /api/rag-chat/sessions/{sessionId}/knowledge-bases

更新关联知识库。**Body**: `{knowledgeBaseIds: number[]}`

### PUT /api/rag-chat/sessions/{sessionId}/pin

置顶会话。

### DELETE /api/rag-chat/sessions/{sessionId}

删除会话。

### /api/rag-chat/sessions/{sessionId}/messages/stream (SSE)

消息流式响应。

---

## 7. 面试日程

### GET /api/interview-schedule?startDate=&endDate=&status=

获取日程列表。

### POST /api/interview-schedule

创建日程。

### POST /api/interview-schedule/parse

解析面试邀请链接。

**Body**: `{url: string}`

### GET /api/interview-schedule/{id}

获取详情。

### PUT /api/interview-schedule/{id}

更新。

### DELETE /api/interview-schedule/{id}

删除。

### PATCH /api/interview-schedule/{id}/status

更新状态。

---

## 8. 语音面试

### GET /api/voice-interview/sessions?status=&page=&size=

获取语音面试会话列表。

### POST /api/voice-interview/sessions

创建语音面试。

### GET /api/voice-interview/sessions/{sessionId}

获取会话详情。

### POST /api/voice-interview/sessions/{sessionId}/end

结束会话。

### GET /api/voice-interview/sessions/{sessionId}/messages

获取消息历史。

### GET /api/voice-interview/sessions/{sessionId}/evaluation

获取评估状态。

### POST /api/voice-interview/sessions/{sessionId}/evaluation

请求评估。

### PUT /api/voice-interview/sessions/{sessionId}/pause

暂停会话。

### PUT /api/voice-interview/sessions/{sessionId}/resume

恢复会话。

### DELETE /api/voice-interview/sessions/{sessionId}

删除会话。

---

## 9. LLM Provider 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/llm-provider/list` | 获取 provider 列表 |
| GET | `/api/llm-provider/{id}` | 获取单个 provider |
| POST | `/api/llm-provider` | 创建 provider |
| PUT | `/api/llm-provider/{id}` | 更新 provider |
| DELETE | `/api/llm-provider/{id}` | 删除 provider |
| POST | `/api/llm-provider/{id}/test` | 测试连接 |
| POST | `/api/llm-provider/reload` | 重新加载配置 |
| GET | `/api/llm-provider/default-provider` | 获取默认 provider |
| PUT | `/api/llm-provider/default-provider` | 设置默认 provider |
| PUT | `/api/llm-provider/default-embedding-provider` | 设置默认 embedding provider |
| GET | `/api/llm-provider/voice/asr` | 获取 ASR 配置 |
| PUT | `/api/llm-provider/voice/asr` | 设置 ASR 配置 |
| GET | `/api/llm-provider/voice/tts` | 获取 TTS 配置 |
| PUT | `/api/llm-provider/voice/tts` | 设置 TTS 配置 |
| POST | `/api/llm-provider/voice/asr/test` | 测试 ASR |

---

## 当前实现状态

| 模块 | 状态 |
|------|------|
| 简历上传 + Tika 解析 + AI 分析 | ✅ 已实现 |
| 简历列表/详情/删除 | ⚠️ 返回格式需要对齐前端类型 |
| 模拟面试 | ❌ 未实现 |
| 知识库 | ❌ 未实现 |
| RAG 对话 | ❌ 未实现 |
| 技能解析 | ❌ 未实现 |
| 面试日程 | ❌ 未实现 |
| 语音面试 | ❌ 未实现 |
| LLM Provider | ❌ 未实现 |
| PDF 导出 | ❌ 未实现 |
