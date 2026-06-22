package interview.guide.common.model;

/**
 * 异步任务状态枚举。
 * <p>
 * 用于知识库向量化、简历分析、面试评估等所有异步处理的场景。
 * 状态机：PENDING → PROCESSING → COMPLETED / FAILED
 */
public enum AsyncTaskStatus {

    /** 待处理：任务已入队，尚未被消费 */
    PENDING,

    /** 处理中：消费者正在执行该任务 */
    PROCESSING,

    /** 已完成：业务逻辑执行成功 */
    COMPLETED,

    /** 已失败：超过最大重试次数或业务异常 */
    FAILED
}
