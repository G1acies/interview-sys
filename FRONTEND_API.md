# Frontend API 文档

> 本文档由前端 `src/api/` 下的实际调用代码和 `src/types/` 的类型定义自动对齐生成，
> 后端实现必须严格匹配本文档的请求/响应格式。

---

## 通用约定

### 响应格式

所有接口统一使用 HTTP 200 + JSON 响应体：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

- `code === 200` → 成功，前端取 `data`
- `code !== 200` → 失败，前端显示 `message`
- `data` 为 `null` 表示无返回值的操作（如删除、重分析）

### 请求方式

| 请求 | 说明 |
|------|------|
| `GET` | 查询，参数通过 `params` / query string |
| `POST` | 创建或触发操作 |
| `PUT` | 全量更新 |
| `PATCH` | 部分更新（如仅改状态） |
| `DELETE` | 删除 |

### 内容类型

- 默认：`application/json`
- 文件上传：`multipart/form-data`
- PDF 导出：返回 `application/pdf` 二进制（不走 Result 包装）

### 超时

| 场景 | 超时 |
|------|------|
| 普通请求 | 60s |
| 文件上传 | 300s（5分钟） |
| AI 生成/评估（创建面试、提交答案、获取报告、知识库查询） | 180s（3分钟） |

### Base URL

- 开发环境：`http://localhost:8080`
- 生产环境：同域（由 nginx 反向代理）

### 错误处理

后端应尽量返回业务友好的错误信息，前端统一通过 `message` 字段展示给用户。

### 响应结构速查

`data` 字段的具体形态由接口决定，共三种模式：

| 模式 | `data` 的类型 | 对应前端代码 | 示例 |
|------|:-:|------|------|
| **单对象** | `T`（一个 JSON 对象） | `const item: T = await request.get<T>(...)` | 简历详情、面试会话信息 |
| **数组** | `T[]`（JSON 数组 `[...]`） | `const list: T[] = await request.get<T[]>(...)` | 简历列表、所有会话 |
| **空** | `null` | `await request.delete(...)` / `request.post(...)` | 删除、重新分析 |

**复杂嵌套**：当接口返回由多个类组成的数据时，`data` 是一个外层对象，内部包含多个命名字段，每个字段可以是对象或数组。例如 `POST /api/resumes/upload` 返回：

```
data ─┬─ storage ─┬─ fileKey: string
      │           ├─ fileUrl: string
      │           └─ resumeId: number
      │
      ├─ analysis? ─┬─ overallScore: number
      │              ├─ scoreDetail: {…}
      │              ├─ strengths: string[]
      │              └─ suggestions: {…}[]
      │
      ├─ duplicate?: boolean
      └─ message?: string
```

每个接口的响应章节都会明确标注属于哪种模式。

---

## 1. 简历模块

### 1.1 健康检查

```
GET /api/resumes/health
```

**data层级**: 单对象 `{status, service}`

**响应完整 JSON**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "status": "ok",
    "service": "resume"
  }
}
```

---

### 1.2 上传简历

```
POST /api/resumes/upload
```

**Content-Type**: `multipart/form-data`

**请求参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | File | ✅ | PDF / DOCX / DOC / TXT，最大 20MB |

**处理流程**（后端完成）：校验 → 上传 MinIO → 入库（PENDING）→ Tika 解析 → AI 分析 → 返回

**超时**: 300s

**data层级**: **复杂嵌套对象** — `data` 包含 `storage`（对象）+ `analysis?`（嵌套对象）+ `duplicate?`（布尔值）

```
data ─┬─ storage ─┬─ fileKey: string    ← MinIO 存储键，如 "resumes/2026/06/21/xxx.pdf"
      │           ├─ fileUrl: string    ← 文件访问 URL
      │           └─ resumeId: number   ← 简历 ID
      │
      ├─ analysis? ─┬─ overallScore: number      ← 总分 0-100
      │              ├─ scoreDetail ─┬─ contentScore     ← 内容完整性 0-25
      │              │               ├─ structureScore   ← 结构清晰度 0-20
      │              │               ├─ skillMatchScore  ← 技能匹配度 0-25
      │              │               ├─ expressionScore  ← 表达专业性 0-15
      │              │               └─ projectScore     ← 项目经验 0-15
      │              ├─ summary: string               ← 简历摘要
      │              ├─ strengths: string[]            ← 优点列表
      │              ├─ suggestions: {category, priority, issue, recommendation}[]
      │              └─ originalText: string           ← 原始文本
      │
      ├─ duplicate?: boolean   ← 是否重复文件
      └─ message?: string
```

> **为什么不是 `data: UploadResponse` 一个对象？** — 因为 `analysis` 是异步结果，上传时可能是 `null`，前端用可选链 `?.` 安全访问。

---

### 1.3 获取简历列表

```
GET /api/resumes
```

**data层级**: **数组** `data: ResumeListItem[]`

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "filename": "张三_Java_3年.pdf",
      "fileSize": 204800,
      "uploadedAt": "2026-06-21T10:30:00",
      "accessCount": 5,
      "latestScore": 78,
      "lastAnalyzedAt": "2026-06-21T10:35:00",
      "interviewCount": 2,
      "analyzeStatus": "COMPLETED",
      "analyzeError": null,
      "storageUrl": "http://..."
    }
  ]
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | number | ✅ | 主键 |
| `filename` | string | ✅ | 文件名 |
| `fileSize` | number | ✅ | 字节 |
| `uploadedAt` | string | ✅ | ISO 8601 |
| `accessCount` | number | ✅ | 访问次数 |
| `latestScore` | number | ❌ | 最新评分 0-100 |
| `lastAnalyzedAt` | string | ❌ | 最近分析时间 |
| `interviewCount` | number | ✅ | 关联面试次数 |
| `analyzeStatus` | enum | ❌ | PENDING / PROCESSING / COMPLETED / FAILED |
| `analyzeError` | string | ❌ | 分析失败原因 |
| `storageUrl` | string | ❌ | 文件 URL |

---

### 1.4 获取简历详情

```
GET /api/resumes/{id}/detail
```

**data层级**: **复杂嵌套对象** — `data` 是一个大对象，内部包含多个数组字段

```
data ─┬─ id: number
      ├─ filename: string
      ├─ fileSize: number
      ├─ contentType: string
      ├─ storageUrl: string
      ├─ uploadedAt: string
      ├─ accessCount: number
      ├─ resumeText: string            ← Tika 解析出的纯文本
      ├─ analyzeStatus?: string        ← PENDING / PROCESSING / COMPLETED / FAILED
      ├─ analyzeError?: string
      ├─ analyses: AnalysisItem[]      ← 数组：分析记录列表
      |    └─ each: {id, overallScore, contentScore, structureScore,
      |               skillMatchScore, expressionScore, projectScore,
      |               summary, analyzedAt, strengths, suggestions}
      └─ interviews: InterviewItem[]   ← 数组：关联的面试记录列表
```

> **关键**：`data` 下有两个独立的子数组 `analyses` 和 `interviews`，前端分别遍历展示。

---

### 1.5 获取简历统计

```
GET /api/resumes/statistics
```

**data层级**: **单对象** `data: ResumeStats`

```typescript
interface ResumeStats {
  totalCount: number;            // 简历总数
  totalInterviewCount: number;   // 总面试次数
  totalAccessCount: number;      // 总访问次数
}
```

---

### 1.6 重新分析简历

```
POST /api/resumes/{id}/reanalyze
```

**data层级**: **空** `data: null`

无请求体。返回 `{"code":200,"message":"success","data":null}`。

---

### 1.7 删除简历

```
DELETE /api/resumes/{id}
```

**data层级**: **空** `data: null`

> ⚠️ 删除简历时应同时删除关联的面试会话和答案。

---

### 1.8 导出简历分析报告

```
GET /api/resumes/{resumeId}/export
```

**不同之处**：此接口**不走统一的 Result 包装**，直接返回二进制 PDF。

| 响应头 | 值 |
|--------|-----|
| `Content-Type` | `application/pdf` |
| `Content-Disposition` | `attachment; filename*=UTF-8''{文件名}.pdf` |

**错误时**：返回 HTTP 500 + 手动构造的 JSON `{"code": 5001, "message": "..."}`。

---

## 2. 模拟面试模块

### 2.1 获取所有面试会话

```
GET /api/interview/sessions
```

**data层级**: **数组** `data: TextSessionMeta[]`

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "sessionId": "uuid-xxx",
      "skillId": "java-backend",
      "difficulty": "mid",
      "resumeId": 1,
      "totalQuestions": 5,
      "status": "COMPLETED",
      "evaluateStatus": "COMPLETED",
      "evaluateError": null,
      "overallScore": 78,
      "createdAt": "2026-06-21T10:30:00",
      "completedAt": "2026-06-21T11:00:00"
    }
  ]
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | ✅ | UUID |
| `skillId` | string | ✅ | 面试方向，如 "java-backend" |
| `difficulty` | string | ✅ | junior / mid / senior |
| `resumeId` | number\|null | ✅ | 关联简历 ID（通用面试为 null） |
| `totalQuestions` | number | ✅ | 题目总数 |
| `status` | enum | ✅ | CREATED / IN_PROGRESS / COMPLETED / EVALUATED |
| `evaluateStatus` | string\|null | ❌ | null / PENDING / PROCESSING / COMPLETED / FAILED |
| `evaluateError` | string\|null | ❌ | 评估失败原因 |
| `overallScore` | number\|null | ❌ | 总分 0-100 |
| `createdAt` | string | ✅ | ISO 8601 |
| `completedAt` | string\|null | ❌ | 面试完成时间 |

---

### 2.2 创建面试会话

```
POST /api/interview/sessions
```

**Content-Type**: `application/json`

**data层级**: **单对象** `data: InterviewSession`

**请求体**：
```json
{
  "resumeText": "张三，3年Java开发经验...",
  "questionCount": 5,
  "resumeId": 1,
  "skillId": "java-backend",
  "difficulty": "mid"
}
```

**请求体字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `resumeText` | string | ❌ | 简历文本（无简历时传空串） |
| `questionCount` | number | ✅ | 题目数量 3-20 |
| `resumeId` | number | ❌ | 简历 ID |
| `forceCreate` | boolean | ❌ | 强制创建新会话，忽略未完成会话 |
| `llmProvider` | string | ❌ | LLM 提供商标识，如 "dashscope" |
| `skillId` | string | ✅ | 面试主题 ID，如 "java-backend" |
| `difficulty` | string | ❌ | junior / mid / senior |
| `customCategories` | CategoryDTO[] | ❌ | 自定义分类（JD 解析结果） |
| `jdText` | string | ❌ | JD 原文 |

**超时**: 180s（AI 出题需要时间）

**响应**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "uuid-xxx",
    "resumeText": "张三，3年Java开发经验...",
    "totalQuestions": 5,
    "currentQuestionIndex": 0,
    "questions": [
      {
        "questionIndex": 0,
        "question": "Java 中 HashMap 的底层实现是什么？",
        "type": "JAVA",
        "category": "Java 集合",
        "userAnswer": null,
        "score": null,
        "feedback": null
      }
    ],
    "status": "CREATED"
  }
}
```

**data 结构图**：
```
data ─┬─ sessionId: string
      ├─ resumeText: string
      ├─ totalQuestions: number
      ├─ currentQuestionIndex: number
      ├─ questions: InterviewQuestion[]  ← 数组：完整题目列表
      |    └─ each: {questionIndex, question, type, category,
      |               userAnswer, score, feedback}
      └─ status: enum
```

**说明**：
- 前端期望创建时返回完整的 `questions` 列表（含已生成的所有题目）
- 有 `resumeId` 时，若存在未完成的会话且 `forceCreate` 不为 `true`，后端应返回已有会话

---

### 2.3 获取会话信息

```
GET /api/interview/sessions/{sessionId}
```

**data层级**: **单对象** `data: InterviewSession`（同 2.2）

---

### 2.4 获取当前题目

```
GET /api/interview/sessions/{sessionId}/question
```

**data层级**: **单对象** `data: CurrentQuestionResponse`

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "completed": false,
    "question": {
      "questionIndex": 2,
      "question": "请解释 Java 中 volatile 关键字的作用",
      "type": "JAVA",
      "category": "Java 并发",
      "userAnswer": null,
      "score": null,
      "feedback": null
    }
  }
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `completed` | boolean | ✅ | 是否已答完所有题 |
| `question` | object\|null | ❌ | 当前题目（completed=false 时有值） |
| `message` | string | ❌ | 已完成时的提示信息 |

---

### 2.5 提交答案（进入下一题）

```
POST /api/interview/sessions/{sessionId}/answers
```

**data层级**: **单对象** `data: SubmitAnswerResponse`

**请求体**：
```json
{
  "questionIndex": 0,
  "answer": "HashMap 底层是数组+链表+红黑树..."
}
```

**超时**: 180s（AI 评估单题需要时间）

**响应**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "hasNextQuestion": true,
    "nextQuestion": {
      "questionIndex": 1,
      "question": "ConcurrentHashMap 如何保证线程安全？",
      "type": "JAVA",
      "category": "Java 并发",
      "userAnswer": null,
      "score": null,
      "feedback": null
    },
    "currentIndex": 0,
    "totalQuestions": 5
  }
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `hasNextQuestion` | boolean | ✅ | 是否还有下一题（false = 已答完） |
| `nextQuestion` | object\|null | ❌ | 下一题信息 |
| `currentIndex` | number | ✅ | 当前已答题号 |
| `totalQuestions` | number | ✅ | 总题数 |

---

### 2.6 暂存答案（不进入下一题）

```
PUT /api/interview/sessions/{sessionId}/answers
```

**data层级**: **空** `data: null`

**请求体**：
```json
{
  "questionIndex": 0,
  "answer": "暂存的内容..."
}
```

**说明**：仅保存不评估，不推进 `currentQuestionIndex`。用于前端防丢失草稿。

---

### 2.7 生成面试报告

```
GET /api/interview/sessions/{sessionId}/report
```

**data层级**: **复杂嵌套对象** `data: InterviewReport`

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "uuid-xxx",
    "totalQuestions": 5,
    "overallScore": 78,
    "categoryScores": [
      {"category": "JAVA", "score": 80, "questionCount": 2},
      {"category": "MYSQL", "score": 70, "questionCount": 2},
      {"category": "PROJECT", "score": 85, "questionCount": 1}
    ],
    "questionDetails": [
      {
        "questionIndex": 0,
        "question": "HashMap 的底层实现？",
        "category": "JAVA",
        "userAnswer": "数组+链表+红黑树",
        "score": 85,
        "feedback": "回答准确，但可补充负载因子说明"
      }
    ],
    "overallFeedback": "候选人Java基础扎实...",
    "strengths": ["Java 并发理解深入", "项目经验丰富"],
    "improvements": ["MySQL 索引优化可进一步学习"],
    "referenceAnswers": [
      {
        "questionIndex": 0,
        "question": "HashMap 的底层实现？",
        "referenceAnswer": "HashMap 底层是数组...",
        "keyPoints": ["数组+链表", "红黑树", "负载因子"]
      }
    ]
  }
}
```

**data 结构图**：
```
data ─┬─ sessionId: string
      ├─ totalQuestions: number
      ├─ overallScore: number                     ← 总分 0-100
      ├─ categoryScores: CategoryScore[]          ← 数组：各类别得分
      |    └─ each: {category, score, questionCount}
      ├─ questionDetails: QuestionEvaluation[]    ← 数组：每题评估
      |    └─ each: {questionIndex, question, category, userAnswer, score, feedback}
      ├─ overallFeedback: string                  ← 总体评语
      ├─ strengths: string[]                      ← 优势列表
      ├─ improvements: string[]                   ← 改进建议
      └─ referenceAnswers: ReferenceAnswer[]      ← 数组：参考答案
           └─ each: {questionIndex, question, referenceAnswer, keyPoints}
```

**超时**: 180s（AI 评估需要时间）

---

### 2.8 查找未完成会话

```
GET /api/interview/sessions/unfinished/{resumeId}
```

**data层级**: **单对象或空** — 有未完成会话时同 `InterviewSession`（2.2），无时 `data: null`

**说明**：前端在进入面试页面时会先调此接口，有未完成会话则直接恢复。

---

### 2.9 提前交卷

```
POST /api/interview/sessions/{sessionId}/complete
```

**data层级**: **空** `data: null`

**说明**：未答完所有题时提前结束，将状态置为 `COMPLETED`，
触发异步评估（通过 Redis Stream 发送评估任务）。

---

### 2.10 获取面试详情（含完整答案和评估）

```
GET /api/interview/sessions/{sessionId}/details
```

**data层级**: **复杂嵌套对象** `data: InterviewDetail`

```
data ─┬─ id: number
      ├─ sessionId: string
      ├─ totalQuestions: number
      ├─ status: string
      ├─ evaluateStatus?: string          ← PENDING/PROCESSING/COMPLETED/FAILED
      ├─ evaluateError?: string
      ├─ overallScore: number | null
      ├─ overallFeedback: string | null
      ├─ createdAt: string
      ├─ completedAt: string | null
      ├─ questions: unknown[]             ← 题目列表（原始 JSON）
      ├─ strengths: string[]              ← 优势列表
      ├─ improvements: string[]           ← 改进建议
      ├─ referenceAnswers: unknown[]      ← 参考答案
      └─ answers: AnswerItem[]            ← 数组：每题详情
           └─ each: {questionIndex, question, category, userAnswer, score,
                     feedback, referenceAnswer?, keyPoints?, answeredAt}
```

---

### 2.11 导出面试报告 PDF

```
GET /api/interview/sessions/{sessionId}/export
```

**data层级**: **不适用** — 不走 Result 包装，直接返回二进制 PDF。

| 响应头 | 值 |
|--------|-----|
| `Content-Type` | `application/pdf` |
| `Content-Disposition` | `attachment; filename*=UTF-8''模拟面试报告_{sessionId}.pdf` |

---

### 2.12 删除面试会话

```
DELETE /api/interview/sessions/{sessionId}
```

**data层级**: **空** `data: null`

**说明**：级联删除关联的答案。

---

## 3. 技能管理模块

### 3.1 获取所有技能

```
GET /api/interview/skills
```

**data层级**: **数组** `data: SkillDTO[]`

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": "java-backend",
      "name": "Java 后端",
      "description": "Java 后端开发面试",
      "categories": [
        {"key": "JAVA", "label": "Java 基础", "priority": "CORE"},
        {"key": "SPRING", "label": "Spring 框架", "priority": "CORE"},
        {"key": "MYSQL", "label": "MySQL", "priority": "NORMAL"}
      ],
      "isPreset": true,
      "sourceJd": null,
      "persona": "你是资深 Java 技术专家...",
      "display": {
        "icon": "☕",
        "gradient": "from-orange-400 to-red-500",
        "iconBg": "bg-orange-100",
        "iconColor": "text-orange-600"
      }
    }
  ]
}
```

**data 结构图**：
```
data: SkillDTO[]            ← 数组
  └─ each: {
       id, name, description,
       categories: CategoryDTO[]  ← 内嵌数组
       isPreset, sourceJd,
       persona?, display?
     }
```

---

### 3.2 获取单个技能

```
GET /api/interview/skills/{id}
```

**data层级**: **单对象** `data: SkillDTO`（同上）

---

### 3.3 解析 JD 提取分类

```
POST /api/interview/skills/parse-jd
```

**data层级**: **数组** `data: CategoryDTO[]`

**请求体**：
```json
{
  "jdText": "职位描述原文..."
}
```

**响应**：
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {"key": "JAVA", "label": "Java 基础", "priority": "CORE"},
    {"key": "SPRING", "label": "Spring Boot", "priority": "CORE"}
  ]
}
```

---

## 4. 知识库模块

### 4.1 上传知识库文件

```
POST /api/knowledgebase/upload
```

**Content-Type**: `multipart/form-data`

**请求参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | File | ✅ | PDF / DOCX / TXT 等 |
| `name` | string | ❌ | 自定义名称 |
| `category` | string | ❌ | 分类 |

**超时**: 300s

**响应**（`UploadKnowledgeBaseResponse`）：
```typescript
interface UploadKnowledgeBaseResponse {
  knowledgeBase: {
    id: number;
    name: string;
    category: string;
    fileSize: number;
    contentLength: number;
  };
  storage: {
    fileKey: string;
    fileUrl: string;
  };
  duplicate: boolean;                    // 是否重复文件
}
```

---

### 4.2 获取知识库列表

```
GET /api/knowledgebase/list?sortBy={sortBy}&vectorStatus={status}
```

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sortBy` | string | ❌ | 排序方式：`time` / `size` / `access` / `question` |
| `vectorStatus` | string | ❌ | 向量化状态筛选：`PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` |

**响应**（`KnowledgeBaseItem[]`）：
```typescript
interface KnowledgeBaseItem {
  id: number;
  name: string;
  category: string | null;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  lastAccessedAt: string;
  accessCount: number;
  questionCount: number;
  vectorStatus: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  vectorError: string | null;
  chunkCount: number;
}
```

---

### 4.3 获取知识库详情

```
GET /api/knowledgebase/{id}
```

**响应**：同 `KnowledgeBaseItem`（见 4.2）

---

### 4.4 删除知识库

```
DELETE /api/knowledgebase/{id}
```

**响应**：`void`

---

### 4.5 下载知识库文件

```
GET /api/knowledgebase/{id}/download
```

**说明**：不走 Result 包装，直接返回文件二进制。

**响应头**：`Content-Type: application/octet-stream`。

---

### 4.6 获取所有分类

```
GET /api/knowledgebase/categories
```

**响应**：`string[]`（分类名称列表）

---

### 4.7 按分类查询

```
GET /api/knowledgebase/category/{category}
```

**响应**：`KnowledgeBaseItem[]`

---

### 4.8 获取未分类条目

```
GET /api/knowledgebase/uncategorized
```

**响应**：`KnowledgeBaseItem[]`

---

### 4.9 更新分类

```
PUT /api/knowledgebase/{id}/category
```

**请求体**：
```json
{
  "category": "新分类名称"
}
```

**响应**：`void`

---

### 4.10 搜索知识库

```
GET /api/knowledgebase/search?keyword={keyword}
```

**响应**：`KnowledgeBaseItem[]`

---

### 4.11 获取统计信息

```
GET /api/knowledgebase/stats
```

**响应**（`KnowledgeBaseStats`）：
```typescript
interface KnowledgeBaseStats {
  totalCount: number;              // 总数
  totalQuestionCount: number;      // 总问题数
  totalAccessCount: number;        // 总访问次数
  completedCount: number;          // 向量化完成数
  processingCount: number;         // 向量化处理中
}
```

---

### 4.12 重新向量化

```
POST /api/knowledgebase/{id}/revectorize
```

**响应**：`void`

---

### 4.13 RAG 查询（非流式）

```
POST /api/knowledgebase/query
```

**超时**: 180s

**请求体**：
```json
{
  "knowledgeBaseIds": [1, 2],    // 支持多个知识库
  "question": "查询问题"
}
```

**响应**（`QueryResponse`）：
```typescript
interface QueryResponse {
  answer: string;                  // AI 回答
  knowledgeBaseId: number;         // 来源知识库 ID
  knowledgeBaseName: string;       // 来源知识库名称
}
```

---

### 4.14 RAG 查询（流式 SSE）

```
POST /api/knowledgebase/query/stream
```

**请求体**：同 4.13

**响应格式**：Server-Sent Events (SSE)

```
data:回答内容chunk1
data:回答内容chunk2
data:回答内容chunk3
...
```

前端通过 `fetch` + `ReadableStream` 逐块消费，非 `EventSource`。

**说明**：
- 每行 `data:` 后跟一个文本块
- 文本块拼接后为完整 Markdown 回答
- 流结束时 `response.body` 的 `done` 为 `true`

---

## 5. RAG 对话模块

### 5.1 获取会话列表

```
GET /api/rag-chat/sessions
```

**响应**（`RagChatSessionListItem[]`）：
```typescript
interface RagChatSessionListItem {
  id: number;
  title: string;
  messageCount: number;
  knowledgeBaseNames: string[];
  updatedAt: string;
  isPinned: boolean;               // 是否置顶
}
```

---

### 5.2 创建对话会话

```
POST /api/rag-chat/sessions
```

**请求体**：
```json
{
  "knowledgeBaseIds": [1, 2],
  "title": "面试准备咨询"             // 可选
}
```

**响应**（`RagChatSession`）：
```typescript
interface RagChatSession {
  id: number;
  title: string;
  knowledgeBaseIds: number[];
  createdAt: string;
}
```

---

### 5.3 获取会话详情

```
GET /api/rag-chat/sessions/{sessionId}
```

**响应**（`RagChatSessionDetail`）：
```typescript
interface RagChatSessionDetail {
  id: number;
  title: string;
  knowledgeBases: Array<{
    id: number;
    name: string;
    originalFilename: string;
    fileSize: number;
    contentType: string;
    uploadedAt: string;
    lastAccessedAt: string;
    accessCount: number;
    questionCount: number;
  }>;
  messages: Array<{
    id: number;
    type: 'user' | 'assistant';
    content: string;
    createdAt: string;
  }>;
  createdAt: string;
  updatedAt: string;
}
```

---

### 5.4 修改会话标题

```
PUT /api/rag-chat/sessions/{sessionId}/title
```

**请求体**：
```json
{
  "title": "新标题"
}
```

**响应**：`void`

---

### 5.5 更新关联知识库

```
PUT /api/rag-chat/sessions/{sessionId}/knowledge-bases
```

**请求体**：
```json
{
  "knowledgeBaseIds": [1, 3]
}
```

**响应**：`void`

---

### 5.6 置顶/取消置顶

```
PUT /api/rag-chat/sessions/{sessionId}/pin
```

**说明**：切换置顶状态（取反）。

**响应**：`void`

---

### 5.7 删除会话

```
DELETE /api/rag-chat/sessions/{sessionId}
```

**响应**：`void`

---

### 5.8 流式消息

```
POST /api/rag-chat/sessions/{sessionId}/messages/stream
```

**请求体**：
```json
{
  "question": "用户提问内容"
}
```

**响应格式**：Server-Sent Events (SSE)

```
data:回复内容chunk1
data:回复内容chunk2
...
```

**说明**：
- 会话不存在时自动创建
- 返回的 `data:` 行拼接后为完整 Markdown 回复
- 流结束时 `response.body` 的 `done` 为 `true`

---

## 6. 面试日程模块

### 6.1 获取日程列表

```
GET /api/interview-schedule?status={status}&start={start}&end={end}
```

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `status` | string | ❌ | 筛选状态：`PENDING` / `COMPLETED` / `CANCELLED` / `RESCHEDULED` |
| `start` | string | ❌ | 起始时间（ISO 8601） |
| `end` | string | ❌ | 结束时间（ISO 8601） |

**响应**（`InterviewSchedule[]`）：
```typescript
interface InterviewSchedule {
  id: number;
  companyName: string;             // 公司名称
  position: string;                // 职位
  interviewTime: string;           // 面试时间 ISO 8601
  interviewType: 'ONSITE' | 'VIDEO' | 'PHONE';
  meetingLink?: string;            // 会议链接
  roundNumber: number;             // 面试轮次
  interviewer?: string;            // 面试官
  notes?: string;                  // 备注
  status: 'PENDING' | 'COMPLETED' | 'CANCELLED' | 'RESCHEDULED';
  createdAt: string;
  updatedAt: string;
}
```

---

### 6.2 创建日程

```
POST /api/interview-schedule
```

**请求体**（`CreateInterviewRequest`）：
```json
{
  "companyName": "字节跳动",
  "position": "后端开发工程师",
  "interviewTime": "2026-07-15T14:00:00+08:00",
  "interviewType": "VIDEO",
  "meetingLink": "https://xxx",
  "roundNumber": 2,
  "interviewer": "张三",
  "notes": "二面，主要考察系统设计"
}
```

**响应**：同 `InterviewSchedule`（见 6.1）

---

### 6.3 解析面试邀请

```
POST /api/interview-schedule/parse
```

**请求体**：
```json
{
  "rawText": "面试邀请原文内容...",
  "source": "feishu"          // 来源：feishu / tencent / zoom / other
}
```

**响应**（`ParseResponse`）：
```typescript
interface ParseResponse {
  success: boolean;             // 是否解析成功
  data: CreateInterviewRequest | null;
  confidence: number;           // 解析置信度 0-1
  parseMethod: 'rule' | 'ai';  // 解析方式
  log: string;                  // 解析日志
}
```

---

### 6.4 获取日程详情

```
GET /api/interview-schedule/{id}
```

**响应**：同 `InterviewSchedule`（见 6.1）

---

### 6.5 更新日程

```
PUT /api/interview-schedule/{id}
```

**请求体**：同创建（见 6.2）

**响应**：同 `InterviewSchedule`

---

### 6.6 删除日程

```
DELETE /api/interview-schedule/{id}
```

**响应**：`void`

---

### 6.7 更新日程状态

```
PATCH /api/interview-schedule/{id}/status?status={status}
```

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `status` | string | ✅ | `PENDING` / `COMPLETED` / `CANCELLED` / `RESCHEDULED` |

**响应**：同 `InterviewSchedule`

---

## 7. 语音面试模块

### 7.1 创建语音面试会话

```
POST /api/voice-interview/sessions
```

**请求体**（`CreateSessionRequest`）：
```json
{
  "roleType": "interviewer",       // 面试官角色
  "skillId": "java-backend",       // 面试方向
  "difficulty": "mid",             // 难度
  "customJdText": "...",           // 自定义 JD（可选）
  "resumeId": 1,                   // 简历 ID（可选）
  "introEnabled": true,            // 是否开启自我介绍环节
  "techEnabled": true,             // 是否开启技术问答环节
  "projectEnabled": true,          // 是否开启项目问答环节
  "hrEnabled": false,              // 是否开启 HR 问答环节
  "plannedDuration": 30,           // 计划时长（分钟）
  "llmProvider": "dashscope"       // LLM 提供商（可选）
}
```

**响应**（`SessionResponse`）：
```typescript
interface SessionResponse {
  sessionId: number;
  roleType: string;
  currentPhase: string;
  status: string;
  startTime: string;
  plannedDuration: number;
  webSocketUrl: string;            // WebSocket 连接地址
}
```

---

### 7.2 获取会话详情

```
GET /api/voice-interview/sessions/{sessionId}
```

**响应**：同 `SessionResponse`（见 7.1）

---

### 7.3 获取会话列表

```
GET /api/voice-interview/sessions?userId={userId}&status={status}
```

**响应**（`SessionMeta[]`）：
```typescript
interface SessionMeta {
  sessionId: number;
  roleType: string;
  status: string;
  currentPhase: string;
  createdAt: string;
  updatedAt: string;
  actualDuration?: number;
  messageCount: number;
  evaluateStatus?: string;
  evaluateError?: string;
}
```

---

### 7.4 结束会话

```
POST /api/voice-interview/sessions/{sessionId}/end
```

**响应**：`void`

---

### 7.5 获取消息历史

```
GET /api/voice-interview/sessions/{sessionId}/messages
```

**响应**（`InterviewMessage[]`）：
```typescript
interface InterviewMessage {
  id: number;
  sessionId: number;
  messageType: string;
  phase: string;
  userRecognizedText: string;
  aiGeneratedText: string;
  timestamp: string;
  sequenceNum: number;
}
```

---

### 7.6 获取评估状态

```
GET /api/voice-interview/sessions/{sessionId}/evaluation
```

**说明**：前端轮询此接口，直到 `evaluateStatus` 变为 `COMPLETED` 或 `FAILED`。

**响应**（`EvaluationStatusResponse`）：
```typescript
interface EvaluationStatusResponse {
  evaluateStatus: string | null;       // PENDING | PROCESSING | COMPLETED | FAILED
  evaluateError?: string | null;
  evaluation?: VoiceEvaluationDetail | null;  // COMPLETED 时有值
}

interface VoiceEvaluationDetail {
  sessionId: number;
  totalQuestions: number;
  overallScore: number;
  overallFeedback: string;
  strengths: string[];
  improvements: string[];
  answers: Array<{
    questionIndex: number;
    question: string;
    category: string;
    userAnswer: string;
    score: number;
    feedback: string;
    referenceAnswer?: string | null;
    keyPoints?: string[] | null;
  }>;
}
```

---

### 7.7 触发异步评估

```
POST /api/voice-interview/sessions/{sessionId}/evaluation
```

**说明**：手动触发异步评估（会返回当前状态，评估在后台执行）。

**响应**：同 `EvaluationStatusResponse`（见 7.6）

---

### 7.8 暂停/恢复会话

```
PUT /api/voice-interview/sessions/{sessionId}/pause
```

**请求体**：
```json
{
  "reason": "user_initiated"
}
```

**响应**：`void`

---

```
PUT /api/voice-interview/sessions/{sessionId}/resume
```

**响应**：同 `SessionResponse`（见 7.1）

---

### 7.9 删除会话

```
DELETE /api/voice-interview/sessions/{sessionId}
```

**响应**：`void`

---

### 7.10 WebSocket 语音通话

**连接地址**：由创建会话时返回的 `webSocketUrl` 提供

**协议格式**：JSON over WebSocket

**客户端 → 服务端**：

| type | data | 说明 |
|------|------|------|
| `audio` | `{ data: "base64音频", timestamp }` | 发送音频数据 |
| `control` | `{ action: "start" / "stop" / "next_question" / ... }` | 控制消息 |

**服务端 → 客户端**：

| type | data | 说明 |
|------|------|------|
| `subtitle` | `{ text: "识别文本", isFinal: true/false }` | 实时语音识别结果 |
| `audio` | `{ data: "base64音频", text: "回复文本" }` | AI 语音回复 |
| `audio_chunk` | `{ data: "base64 WAV", index, isLast }` | 音频分块传输 |
| `text` | `{ content: "文本", final: true/false }` | AI 文本回复 |
| `control` | `{ action: "...", message: "..." }` | 控制消息 |
| `error` | `{ message: "错误描述" }` | 错误消息 |

---

## 8. LLM Provider 管理

### 8.1 获取 Provider 列表

```
GET /api/llm-provider/list
```

**响应**（`ProviderItem[]`）：
```typescript
interface ProviderItem {
  id: string;                      // 唯一标识
  baseUrl: string;                 // API 地址
  maskedApiKey: string;            // 脱敏后的 API Key
  model: string;                   // 模型名
  embeddingModel: string | null;   // Embedding 模型名
  embeddingDimensions: number | null;
  supportsEmbedding: boolean;
  temperature: number | null;
  defaultChatProvider: boolean;    // 默认 Chat Provider
  defaultEmbeddingProvider: boolean;
}
```

---

### 8.2 获取单个 Provider

```
GET /api/llm-provider/{id}
```

**响应**：同 `ProviderItem`

---

### 8.3 创建 Provider

```
POST /api/llm-provider
```

**请求体**：
```json
{
  "id": "my-provider",
  "baseUrl": "https://api.openai.com/v1",
  "apiKey": "sk-xxx",
  "model": "gpt-4",
  "embeddingModel": "text-embedding-3-small",
  "embeddingDimensions": 1024,
  "supportsEmbedding": true,
  "temperature": 0.7
}
```

**响应**：`void`

---

### 8.4 更新 Provider

```
PUT /api/llm-provider/{id}
```

**请求体**：所有字段可选
```json
{
  "baseUrl": "https://new-url",
  "model": "gpt-4o"
}
```

**响应**：`void`

---

### 8.5 删除 Provider

```
DELETE /api/llm-provider/{id}
```

**响应**：`void`

---

### 8.6 测试连接

```
POST /api/llm-provider/{id}/test
```

**响应**（`ProviderTestResult`）：
```typescript
interface ProviderTestResult {
  success: boolean;
  message: string;
  model: string;
}
```

---

### 8.7 重新加载配置

```
POST /api/llm-provider/reload
```

**说明**：从数据库重新加载所有 Provider 配置到内存缓存。

**响应**：`void`

---

### 8.8 获取/设置默认 Provider

```
GET /api/llm-provider/default-provider
```

**响应**（`DefaultProvider`）：
```typescript
interface DefaultProvider {
  defaultProvider: string;
  defaultEmbeddingProvider: string;
}
```

```
PUT /api/llm-provider/default-provider
```

**请求体**：同 `DefaultProvider`

**响应**：`void`

---

### 8.9 设置默认 Embedding Provider

```
PUT /api/llm-provider/default-embedding-provider
```

**请求体**：同 `DefaultProvider`

**响应**：`void`

---

### 8.10 语音 ASR/TTS 配置

```
GET /api/llm-provider/voice/asr
```

**响应**（`AsrConfig`）：
```typescript
interface AsrConfig {
  url: string;
  model: string;
  maskedApiKey: string;
  language: string;
  format: string;
  sampleRate: number;
  enableTurnDetection: boolean;
  turnDetectionType: string;
  turnDetectionThreshold: number;
  turnDetectionSilenceDurationMs: number;
}
```

```
PUT /api/llm-provider/voice/asr
```

**请求体**（`AsrConfigRequest`，所有字段可选）：
```json
{
  "url": "wss://asr-url",
  "model": "asr-model",
  "apiKey": "sk-xxx"
}
```

```
GET /api/llm-provider/voice/tts
```

**响应**（`TtsConfig`）：
```typescript
interface TtsConfig {
  model: string;
  maskedApiKey: string;
  voice: string;
  format: string;
  sampleRate: number;
  mode: string;
  languageType: string;
  speechRate: number;
  volume: number;
}
```

```
PUT /api/llm-provider/voice/tts
```

**请求体**（`TtsConfigRequest`，所有字段可选）

```
POST /api/llm-provider/voice/asr/test
```

**响应**：同 `ProviderTestResult`

---

## 实现状态

| 模块 | 状态 | 备注 |
|------|------|------|
| 简历上传/列表/详情/删除/重分析 | ✅ 已实现 | 含 Tika 解析 + AI 分析 |
| 简历 PDF 导出 | ✅ 已实现 | iText 8 |
| **模拟面试核心流程** | 🚧 实现中 | 会话创建/答题/报告（第1组Entity/Repo已完成） |
| 面试历史/详情/删除 | ❌ 未实现 | 依赖面试模块完成 |
| 知识库 CRUD + 向量化 | ❌ 未实现 | 需 pgvector |
| 知识库 RAG 查询（流式+非流式） | ❌ 未实现 | 需 pgvector |
| RAG 对话 | ❌ 未实现 | 依赖知识库 |
| 技能管理 | ❌ 未实现 | Skill 定义 + JD 解析 |
| 面试日程 | ❌ 未实现 | 含邀请解析 |
| 语音面试（REST + WebSocket） | ❌ 未实现 | 需 ASR/TTS |
| LLM Provider 管理 | ❌ 未实现 | 多 Provider 路由 |

---

 ## 需要按顺序创建的 5 组代码

### 第一组：基础模型 + Repository（持久化基石）

文件: AsyncTaskStatus.java
说明: 通用枚举 PENDING/PROCESSING/COMPLETED/FAILED

文件: CommonConstants.java（新增 InterviewDefaults）
说明: 面试默认值 SKILL_ID, DIFFICULTY

文件: InterviewSessionEntity.java
说明: JPA 实体 — 面试会话表

文件: InterviewAnswerEntity.java
说明: JPA 实体 — 面试答案表

文件: InterviewSessionRepository.java
说明: JPA Repository（按 sessionId / resumeId 查询）

文件: InterviewAnswerRepository.java
说明: JPA Repository（按 session + questionIndex 查询）

### 第二组：所有 Model/Request/Response/Record

文件: CreateInterviewRequest.java
说明: 创建会话请求体

文件: SubmitAnswerRequest.java
说明: 提交答案请求体

文件: SubmitAnswerResponse.java
说明: 提交答案响应（hasNextQuestion, nextQuestion 等）

文件: SessionListItemDTO.java
说明: 列表项 DTO（含 from() 工厂方法）

文件: InterviewDetailDTO.java
说明: 详情 DTO（含嵌套 AnswerDetailDTO）

文件: InterviewReportDTO.java
说明: 评估报告 DTO（含 CategoryScore, QuestionEvaluation, ReferenceAnswer）

文件: HistoricalQuestion.java
说明: 历史题目 record（出题去重用）

文件: InterviewHistoryItemDTO.java
说明: 简历详情页关联的面试记录 DTO

### 第三组：AI 评估基础设施（跨模块通用）

 文件                               说明
 ────────────────────────────────── ──────────────────────────────────
 UnifiedEvaluationService.java      统一的批次评估 + 二次汇总服务
 EvaluationReport.java              通用评估报告 record
 QaRecord.java                      通用问答记录 record
 InterviewEvaluationProperties.java 评估配置（batchSize, prompt 路径）

### 第四组：面试核心 Service + Controller

文件: InterviewPersistenceService.java
说明: 持久化层 — 保存会话、答案、报告、查找历史题目

文件: InterviewQuestionProperties.java
说明: 出题配置（prompt 路径、follow-up 数量）

文件: InterviewQuestionService.java
说明: AI 出题 — 有简历/无简历双模式、并行生成、追问题

文件: AnswerEvaluationService.java
说明: DTO 适配器 — 调用 UnifiedEvaluationService

文件: InterviewSessionService.java
说明: 核心 — 会话生命周期（创建、答题、交卷、报告），用到 Redis 缓存

文件: InterviewHistoryService.java
说明: 会话详情 + PDF 导出

文件: InterviewController.java
说明: 11 个 REST 接口（映射到前端 interviewApi）

### 第五组：异步评估（Redis Stream）

 文件                          说明
 ───────────────────────────── ────────────────────────────────────────────────────────
 AsyncTaskStreamConstants.java Stream key/group/field 常量
 AbstractStreamProducer.java   生产者模板
 AbstractStreamConsumer.java   消费者模板
 EvaluateStreamProducer.java   面试评估任务生产者
 EvaluateStreamConsumer.java   面试评估任务消费者（反序列化问题 → 调用评估 → 保存报告）

 第六组：可选但依赖的周边

 文件                          说明
 ───────────────────────────── ───────────────────────────────────────────────
 InterviewMapper.java          MapStruct 映射器（Entity → DTO）
 InterviewSkillService.java    Skill 管理（分类分配、JD 解析、Reference 注入）
 InterviewSkillProperties.java Skill 配置定义
 InterviewSkillController.java Skill 查询接口
 Prompt 模板（8 个 .st 文件）  resources/prompts/ 目录下的面试出题和评估提示词

 ─────────────────────────────────────────────────────────────────────────────────────────────

 推荐实施顺序

 1. 第一组（实体/Repository）→ 建表基础
 2. 第二组（Model records）→ 数据传输对象
 3. 第三组（统一评估）→ AI 评估引擎
 4. 第四组（Service + Controller 配齐前七步：Persistence → Question → AnswerEval → Session → History
     → 然后 Controller）→ 核心业务流程

 5. 第五组（异步评估）→ 完成后  POST /complete  触发异步评估报告生成
 6. 第六组（Skill/Mapper/Prompt）→ 按需补全
