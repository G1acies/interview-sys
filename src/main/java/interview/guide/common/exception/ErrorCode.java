package interview.guide.common.exception;

public enum ErrorCode {

    // 通用错误 1xxx
    BAD_REQUEST(400, "请求参数错误"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // 简历模块 2xxx
    RESUME_NOT_FOUND(2001, "简历不存在"),
    RESUME_PARSE_FAILED(2002, "简历解析失败"),
    RESUME_ANALYSIS_FAILED(2003, "简历分析失败"),
    RESUME_DUPLICATE(2004, "简历已存在"),

    // 面试模块 3xxx
    INTERVIEW_SESSION_NOT_FOUND(3001, "面试会话不存在"),
    INTERVIEW_EVALUATION_FAILED(3002, "面试评估失败"),
    INTERVIEW_EXPORT_FAILED(3003, "面试报告导出失败"),

    // 存储模块 4xxx
    STORAGE_UPLOAD_FAILED(4001, "文件上传失败"),
    STORAGE_DOWNLOAD_FAILED(4002, "文件下载失败"),
    STORAGE_DELETE_FAILED(4003, "文件删除失败"),

    // 导出模块 5xxx
    EXPORT_PDF_FAILED(5001, "PDF 导出失败"),

    // 知识库模块 6xxx
    KNOWLEDGE_BASE_NOT_FOUND(6001, "知识库不存在"),
    KNOWLEDGE_BASE_VECTORIZE_FAILED(6002, "知识库向量化失败"),

    // AI 服务 7xxx
    AI_SERVICE_TIMEOUT(7001, "AI 服务超时"),
    AI_SERVICE_ERROR(7002, "AI 服务调用失败"),
    AI_STRUCTURED_OUTPUT_FAILED(7003, "AI 结构化输出失败"),

    // 限流模块 8xxx
    RATE_LIMIT_EXCEEDED(8001, "请求频率超限"),

    // 面试日程模块 9xxx
    INTERVIEW_SCHEDULE_NOT_FOUND(9001, "面试日程不存在"),

    // 语音面试模块 10xxx
    VOICE_SESSION_NOT_FOUND(10001, "语音面试会话不存在"),
    VOICE_SESSION_CLOSED(10002, "语音面试会话已关闭");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
