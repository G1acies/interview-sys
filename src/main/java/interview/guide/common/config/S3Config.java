package interview.guide.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * 创建 S3Client 并注册为 Spring Bean，供 FileStorageService 注入使用。
 * <p>
 * 关键配置 {@code forcePathStyle(true)} — MinIO 不支持 AWS 的虚拟主机寻址
 * （{@code bucket.endpoint}），必须用路径风格（{@code endpoint/bucket/key}），
 * 否则上传时会报 DNS 解析错误。
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final StorageConfigProperties storageConfig;

    /**
     * 构建 S3 客户端。
     * <p>
     * S3Client 是线程安全的，全局单例即可。
     * 这里只做参数校验，不会真正连接服务器，所以 MinIO 离线时启动也不会报错。
     */
    @Bean
    public S3Client s3Client() {
        // 把配置中的 accessKey / secretKey 包装成 AWS 凭证对象
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                storageConfig.getAccessKey(),
                storageConfig.getSecretKey()
        );

        return S3Client.builder()
                // 指向 MinIO 而不是真正的 AWS S3
                .endpointOverride(URI.create(storageConfig.getEndpoint()))
                .region(Region.of(storageConfig.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                // 路径风格访问: http://localhost:9000/bucket/key
                // 而不是虚拟主机风格: http://bucket.localhost:9000/key
                .forcePathStyle(true)
                .build();
    }
}
