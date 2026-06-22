package interview.guide.infrastructure.redis;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 面试会话 Redis 缓存服务。
 *
 * <h3>为什么需要 Redis</h3>
 * 面试中每答一题就要更新当前进度、保存答案，频率很高。
 * 如果每题都直接写 MySQL，不仅慢，还会占用数据库连接。
 * Redis 作为内存数据库，读写快，适合做面试过程的实时数据存储。
 *
 * <h3>Redis 与 MySQL 的双写策略</h3>
 * <ol>
 *   <li><b>实时数据</b>（当前进度、答案）→ 先写 Redis，给用户即时反馈</li>
 *   <li><b>持久化</b> → 同时异步写到 MySQL，保证数据不丢</li>
 *   <li><b>恢复</b> → Redis 宕机后，从 MySQL 恢复数据到 Redis</li>
 * </ol>
 *
 * <h3>Redis Key 设计</h3>
 * <pre>
 * interview:session:{sessionId}   — 会话完整数据（CachedSession 对象）
 * interview:resume:{resumeId}     — 简历 ID → 未完成会话 ID 的映射
 * </pre>
 * 所有 key 统一前缀，方便管理和按模式删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionCache {

    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    // Key 前缀：冒号分隔的命名空间，Redis 管理台可以直接按前缀浏览
    private static final String SESSION_KEY_PREFIX = "interview:session:";
    // 简历到会话的映射前缀：用于快速查找某份简历是否有未完成的面试
    private static final String RESUME_SESSION_KEY_PREFIX = "interview:resume:";
    // 会话过期时间：24 小时未完成的面试自动过期，防止 Redis 堆积
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    /**
     * 缓存的会话数据 — 存放在 Redis 中的值对象。
     * <p>
     * 实现 {@link Serializable} 是因为 Redisson 默认用 JDK 序列化，
     * 必须标记为可序列化，否则 Redisson 会抛异常。
     *
     * <h3>为什么 questions 存的是 JSON 字符串而不是对象</h3>
     * 假设直接存 {@code List<InterviewQuestionDTO>}，Redisson 会尝试用 JDK 序列化，
     * 但 DTO 是 {@code record} 类型，可能跨版本兼容性不好。
     * 转为 JSON 字符串存，用 Jackson 手动序列化/反序列化，更可控。
     */
    @Data
    public static class CachedSession implements Serializable {
        private String sessionId;
        private String resumeText;
        private Long resumeId;

        /**
         * 问题列表的 JSON 字符串。
         * 每次更新答案时整个重新序列化，因为 Redisson 不感知对象内部字段变化。
         */
        private String questionsJson;
        private int currentIndex;
        private SessionStatus status;

        public CachedSession() {}

        /**
         * 全参构造器 — 自动将 question 列表序列化为 JSON 字符串。
         * 序列化失败时抛 BusinessException，调用方无需关心 JSON 细节。
         */
        public CachedSession(String sessionId, String resumeText, Long resumeId,
                            List<InterviewQuestionDTO> questions, int currentIndex,
                            SessionStatus status, ObjectMapper om) {
            this.sessionId = sessionId;
            this.resumeText = resumeText;
            this.resumeId = resumeId;
            this.currentIndex = currentIndex;
            this.status = status;
            try {
                this.questionsJson = om.writeValueAsString(questions);
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "序列化问题列表失败: " + e.getMessage());
            }
        }

        /**
         * 反序列化 questionsJson 为问题列表。
         * {@link TypeReference} 保留了泛型类型信息，Jackson 能正确推断 List 的元素类型。
         */
        public List<InterviewQuestionDTO> getQuestions(ObjectMapper om) {
            try {
                return om.readValue(questionsJson, new TypeReference<>() {});
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "反序列化问题列表失败");
            }
        }
    }

    /**
     * 保存会话到 Redis。
     *
     * <h3>做了两件事</h3>
     * <ol>
     *   <li>存 {@code interview:session:{sessionId}} → CachedSession（含全部问题 + 进度）</li>
     *   <li>如果有 resumeId 且会话未完成，存 {@code interview:resume:{resumeId}} → sessionId，
     *       用于快速查找"这份简历有没有正在进行的面试"</li>
     * </ol>
     */
    public void saveSession(String sessionId, String resumeText, Long resumeId,
                           List<InterviewQuestionDTO> questions, int currentIndex,
                           SessionStatus status) {
        CachedSession cached = new CachedSession(
                sessionId, resumeText, resumeId, questions, currentIndex, status, objectMapper);
        redisService.set(buildSessionKey(sessionId), cached, SESSION_TTL);

        // 映射关系只在"未完成"状态时需要（已完成/已评估的会话不需要被检索）
        if (resumeId != null && (status == SessionStatus.CREATED
                || status == SessionStatus.IN_PROGRESS)) {
            redisService.set(buildResumeSessionKey(resumeId), sessionId, SESSION_TTL);
        }

        log.debug("会话已缓存: sessionId={}, resumeId={}, status={}", sessionId, resumeId, status);
    }

    /** 获取缓存的会话。可能为 null（缓存过期或 key 不存在）。 */
    public Optional<CachedSession> getSession(String sessionId) {
        CachedSession s = redisService.get(buildSessionKey(sessionId));
        return Optional.ofNullable(s);
    }

    /**
     * 更新会话状态。
     * 当面试完成或开始评估时调此方法更新状态，同时清理 resume→session 映射。
     */
    public void updateSessionStatus(String sessionId, SessionStatus status) {
        getSession(sessionId).ifPresent(s -> {
            s.setStatus(status);
            redisService.set(buildSessionKey(sessionId), s, SESSION_TTL);

            // 会话结束后不再需要 resumeId 的映射，及时清理避免干扰下一次面试
            if (status == SessionStatus.COMPLETED || status == SessionStatus.EVALUATED) {
                if (s.getResumeId() != null) {
                    redisService.delete(buildResumeSessionKey(s.getResumeId()));
                }
            }
        });
    }

    /** 更新当前进度：答题后 currentIndex + 1 */
    public void updateCurrentIndex(String sessionId, int currentIndex) {
        getSession(sessionId).ifPresent(s -> {
            s.setCurrentIndex(currentIndex);
            redisService.set(buildSessionKey(sessionId), s, SESSION_TTL);
        });
    }

    /**
     * 更新问题列表（保存答案后调用）。
     * 每次更新是整个问题列表重新序列化，因为 Redisson 的 RBucket 无法感知对象内部字段变化。
     */
    public void updateQuestions(String sessionId, List<InterviewQuestionDTO> questions) {
        getSession(sessionId).ifPresent(s -> {
            try {
                s.setQuestionsJson(objectMapper.writeValueAsString(questions));
                redisService.set(buildSessionKey(sessionId), s, SESSION_TTL);
            } catch (Exception e) {
                log.error("序列化问题列表失败", e);
            }
        });
    }

    /** 删除会话缓存（面试记录被删除时清理） */
    public void deleteSession(String sessionId) {
        getSession(sessionId).ifPresent(s -> {
            if (s.getResumeId() != null) {
                redisService.delete(buildResumeSessionKey(s.getResumeId()));
            }
        });
        redisService.delete(buildSessionKey(sessionId));
    }

    /**
     * 按简历 ID 查找未完成会话。
     * 先通过 resumeId 找到 sessionId，再验证该会话确实存在且未完成。
     * 如果会话已过期/已完成，清理映射并返回 empty。
     */
    public Optional<String> findUnfinishedSessionId(Long resumeId) {
        String key = buildResumeSessionKey(resumeId);
        String sessionId = redisService.get(key);
        if (sessionId != null) {
            Optional<CachedSession> sessionOpt = getSession(sessionId);
            if (sessionOpt.isPresent() && (sessionOpt.get().getStatus() == SessionStatus.CREATED
                    || sessionOpt.get().getStatus() == SessionStatus.IN_PROGRESS)) {
                return Optional.of(sessionId);
            }
            // 映射存在但会话已无效（过期 or 已完成），删除映射
            redisService.delete(key);
        }
        return Optional.empty();
    }

    /** 刷新过期时间：每次操作会话时调用，延长 24 小时寿命 */
    public void refreshSessionTTL(String sessionId) {
        redisService.expire(buildSessionKey(sessionId), SESSION_TTL);
    }

    private String buildSessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String buildResumeSessionKey(Long resumeId) {
        return RESUME_SESSION_KEY_PREFIX + resumeId;
    }
}
