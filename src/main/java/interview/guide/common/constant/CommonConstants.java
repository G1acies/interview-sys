package interview.guide.common.constant;

/**
 * 通用常量定义。
 * <p>
 * 所有模块共用的常量集中管理，避免散落在各处。
 * 子命名空间用静态内部类隔离，按业务域分组。
 */
public final class CommonConstants {

    private CommonConstants() {
        // 工具类，禁止实例化
    }

    /**
     * HTTP 状态码常量。
     */
    public static final class StatusCode {
        public static final int SUCCESS = 200;
        public static final int BAD_REQUEST = 400;
        public static final int UNAUTHORIZED = 401;
        public static final int FORBIDDEN = 403;
        public static final int NOT_FOUND = 404;
        public static final int SERVER_ERROR = 500;

        private StatusCode() {
        }
    }

    /**
     * 分页默认值。
     */
    public static final class Pagination {
        public static final int DEFAULT_PAGE = 1;
        public static final int DEFAULT_SIZE = 20;
        public static final int MAX_SIZE = 100;

        private Pagination() {
        }
    }

    /**
     * 面试模块默认值。
     * <p>
     * 当调用方未指定 skillId 或 difficulty 时使用这些默认值。
     */
    public static final class InterviewDefaults {
        /** 默认面试方向：Java 后端 */
        public static final String SKILL_ID = "java-backend";

        /** 默认难度级别：中级 */
        public static final String DIFFICULTY = "mid";

        private InterviewDefaults() {
        }
    }
}
