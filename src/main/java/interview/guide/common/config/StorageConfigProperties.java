package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * S3 兼容存储（MinIO）的连接配置。
 * <p>
 * 从 application.yml 中 {@code app.storage.*} 读取，
 * 支持通过环境变量覆盖（如 {@code APP_STORAGE_ENDPOINT}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageConfigProperties {

    /** MinIO 服务地址，如 http://localhost:9000 */
    private String endpoint;

    /** S3 Access Key（MinIO 的登录账号） */
    private String accessKey;

    /** S3 Secret Key（MinIO 的登录密码） */
    private String secretKey;

    /** 存储桶名称，所有文件都存在这个桶下 */
    private String bucket;

    /** 区域标识，MinIO 可随意填，默认 us-east-1 */
    private String region = "us-east-1";
}
