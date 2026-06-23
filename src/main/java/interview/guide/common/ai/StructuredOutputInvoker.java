package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * 统一封装结构化输出调用与重试策略。
 *
 * <h3>为什么需要这个类？</h3>
 * <p>
 * AI 面试系统的核心依赖 LLM 输出<strong>结构化 JSON</strong>（如面试题列表、评估报告）。
 * 但 LLM 本质上是文本生成模型，不是 JSON 序列化器——它可能：
 * </p>
 * <ul>
 *   <li>在 JSON 外包一层 markdown 代码块（```json ... ```）</li>
 *   <li>在 JSON 前后添加额外说明文字（"以下是您需要的题目："）</li>
 *   <li>JSON 格式有误（末尾多逗号、引号未转义）</li>
 *   <li>直接返回空内容或报错信息</li>
 * </ul>
 * <p>
 * Spring AI 的 {@link BeanOutputConverter} 可以解析结构化输出，但一旦 LLM 返回格式错误的 JSON，
 * 解析就会失败。本类在这个基础上增加了<strong>自动重试</strong>能力：
 * 解析失败时带上更严格的格式指令重新调用 LLM，而不是直接让业务层处理异常。
 * </p>
 *
 * <h3>重试策略</h3>
 * <p>
 * 每次重试时，会在 system prompt 末尾追加一段<strong>严格 JSON 指令</strong>
 * （{@link #STRICT_JSON_INSTRUCTION}），强调：
 * </p>
 * <ol>
 *   <li>不要输出 markdown 代码块</li>
 *   <li>不要输出任何解释文字</li>
 *   <li>所有字符串内引号必须正确转义</li>
 * </ol>
 * <p>
 * 重试次数由 {@link StructuredOutputProperties#getStructuredMaxAttempts()} 控制（默认 2 次）。
 * 所有重试耗尽后抛出 {@link BusinessException}，错误信息包含最后一次失败的详情。
 * </p>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 定义一个 outputConverter 描述期望的 JSON 结构
 * BeanOutputConverter<QuestionListDTO> converter =
 *     new BeanOutputConverter<>(QuestionListDTO.class);
 *
 * // 调用（重试细节完全透明）
 * QuestionListDTO dto = structuredOutputInvoker.invoke(
 *     chatClient,
 *     systemPrompt + "\n\n" + converter.getFormat(),  // 追加格式说明
 *     userPrompt,
 *     converter,
 *     ErrorCode.AI_STRUCTURED_OUTPUT_FAILED,
 *     "题目生成失败：",
 *     "出题",
 *     log
 * );
 * }</pre>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li><b>为什么 systemPrompt 由调用方拼接格式说明？</b>
 *       — 不同场景的格式说明位置不同（评价类放末尾，出题类放中间），
 *       由调用方控制最灵活。</li>
 *   <li><b>为什么传 Logger 而不直接用类 Logger？</b>
 *       — 调用方可能是 InterviewQuestionService 或 UnifiedEvaluationService，
 *       日志应该归属于调用方的 Logger，方便定位问题。</li>
 *   <li><b>为什么用 ErrorCode 而不是固定错误码？</b>
 *       — 不同的调用场景需要不同的错误码（出题失败用 7xxx，评估失败用 3xxx），
 *       调用方根据业务域传入对应的 ErrorCode。</li>
 * </ul>
 *
 * @see StructuredOutputProperties 重试配置
 * @see BeanOutputConverter        Spring AI 的结构化输出转换器
 */
@Component
public class StructuredOutputInvoker {

    /**
     * 重试时追加的严格 JSON 指令。
     * <p>
     * LLM 首次输出时可能不够严格，追加此指令可以"纠正"其行为。
     * 内容聚焦于三件事：禁止 markdown 块、禁止额外文字、正确转义引号。
     */
    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
    """;

    /** 最大重试次数（至少 1 次） */
    private final int maxAttempts;

    /** 重试时是否追加严格 JSON 指令 */
    private final boolean appendStrictJsonInstruction;

    public StructuredOutputInvoker(StructuredOutputProperties properties) {
        this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
        this.appendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
    }

    /**
     * 调用 LLM 并解析结构化输出。
     * <p>
     * 这是本类的核心方法，流程如下：
     * <ol>
     *   <li>组装 systemPrompt + userPrompt，调用 {@link ChatClient}</li>
     *   <li>用 {@link BeanOutputConverter#convert(String)} 将响应解析为目标类型</li>
     *   <li>解析成功 → 立即返回</li>
     *   <li>解析失败 → 记录错误，第 2+ 次重试时追加 {@link #STRICT_JSON_INSTRUCTION}</li>
     *   <li>重试耗尽 → 抛出 {@link BusinessException}</li>
     * </ol>
     *
     * <h3>注意事项</h3>
     * <ul>
     *   <li>调用方负责在 {@code systemPrompt} 中追加 {@code converter.getFormat()}，
     *       告知 LLM 期望的输出字段结构。本类不自动追加，因为追加位置因场景而异。</li>
     *   <li>参数 {@code taskName} 仅用于日志中的任务标识，不影响业务逻辑。</li>
     * </ul>
     *
     * @param chatClient    Spring AI 聊天客户端（由 LlmProviderRegistry 或 ChatClient.Builder 创建）
     * @param systemPrompt  系统提示词（调用方需自行追加 {@code converter.getFormat()}）
     * @param userPrompt    用户提示词（含具体数据，如简历文本、问答记录）
     * @param converter     Spring AI 输出转换器，描述期望的 JSON 结构
     * @param errorCode     所有重试均失败时抛出的业务错误码
     * @param errorPrefix   错误消息前缀，如 "题目生成失败："
     * @param taskName      任务名称（仅用于日志），如 "出题"、"批次评估"、"总结评估"
     * @param log           调用方的 SLF4J Logger，日志归属到调用方类名下
     * @param <T>           目标类型（由 {@code converter} 的类型参数推断）
     * @return 解析后的结构化对象
     * @throws BusinessException 当所有重试都失败时抛出，携带 {@code errorCode} 和最后一次错误信息
     */
    public <T> T invoke(
            ChatClient chatClient,
            String systemPrompt,
            String userPrompt,
            BeanOutputConverter<T> converter,
            ErrorCode errorCode,
            String errorPrefix,
            String taskName,
            Logger log) {

        // 记录最后一次错误信息，重试耗尽时用于构造异常消息
        String lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // 第 2+ 次重试时追加严格 JSON 指令，提高格式正确率
                String finalSystemPrompt = systemPrompt;
                if (attempt > 1 && appendStrictJsonInstruction) {
                    finalSystemPrompt = systemPrompt + "\n\n" + STRICT_JSON_INSTRUCTION;
                }

                // 调用 LLM
                String response = chatClient.prompt()
                        .system(finalSystemPrompt)
                        .user(userPrompt)
                        .call()
                        .content();

                if (response == null || response.isBlank()) {
                    throw new RuntimeException("AI 返回空内容");
                }

                // 尝试结构化输出转换
                return converter.convert(response);

            } catch (Exception e) {
                // 提取并截断错误消息（防止过长撑爆日志和异常消息）
                lastError = e.getMessage() != null ? e.getMessage() : "未知错误";
                if (lastError.length() > 200) {
                    lastError = lastError.substring(0, 200);
                }

                if (attempt < maxAttempts) {
                    log.warn("{}第{}次尝试失败，将重试: {}", errorPrefix, attempt, lastError);
                } else {
                    log.error("{}重试耗尽，共尝试{}次: {}", errorPrefix, maxAttempts, lastError);
                }
            }
        }

        // 所有重试耗尽，抛出业务异常
        throw new BusinessException(errorCode,
                errorPrefix + "重试" + maxAttempts + "次后仍然失败: " + lastError);
    }
}
