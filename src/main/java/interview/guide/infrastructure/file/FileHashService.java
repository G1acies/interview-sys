package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件哈希计算服务。
 * <p>
 * 统一提供 SHA-256 哈希计算，用于文件去重。
 * 知识库和简历模块上传时先计算哈希，与数据库已有记录比对，
 * 相同内容的文件不上传，避免存储浪费和重复向量化。
 * <p>
 * 支持三种输入形式：
 * <ul>
 *   <li>{@link MultipartFile} — 上传场景</li>
 *   <li>{@code byte[]} — 已读入内存的文件</li>
 *   <li>{@link InputStream} — 大文件流式计算</li>
 * </ul>
 */
@Slf4j
@Service
public class FileHashService {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 8192;

    /**
     * 计算 {@link MultipartFile} 的 SHA-256 哈希值。
     *
     * @param file 上传的文件
     * @return 64 位十六进制小写哈希字符串
     */
    public String calculateHash(MultipartFile file) {
        try {
            return calculateHash(file.getBytes());
        } catch (IOException e) {
            log.error("读取文件内容失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }

    /**
     * 计算字节数组的 SHA-256 哈希值。
     *
     * @param data 文件字节数组
     * @return 64 位十六进制小写哈希字符串
     */
    public String calculateHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(data);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("哈希算法不支持: {}", HASH_ALGORITHM, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }

    /**
     * 流式计算文件的 SHA-256 哈希值。
     * <p>
     * 适用于大文件场景，避免一次性将整个文件读入内存。
     *
     * @param inputStream 文件输入流
     * @return 64 位十六进制小写哈希字符串
     */
    public String calculateHash(InputStream inputStream) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            log.error("计算文件哈希失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }

    /**
     * 将字节数组转换为十六进制字符串。
     *
     * @param bytes 哈希字节数组
     * @return 小写十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
