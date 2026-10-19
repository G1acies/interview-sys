package interview.guide.modules.llmprovider.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * API Key 加密/解密服务。
 * <p>
 * 使用 Base64 编码存储（非真正的加密），防止明文 API Key 直接出现在数据库中。
 * 如果需要更高的安全性，可以替换为 AES 加密（密钥从环境变量读取）。
 * <p>
 * 注意：生产环境应使用 AES-256-GCM 替代 Base64。
 */
@Slf4j
@Service
public class ApiKeyEncryptionService {

    /**
     * 加密（编码）API Key。
     *
     * @param rawKey 原始 API Key
     * @return Base64 编码后的字符串
     */
    public String encrypt(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return "";
        return Base64.getEncoder().encodeToString(rawKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解密（解码）API Key。
     *
     * @param encryptedKey Base64 编码的 API Key
     * @return 原始 API Key
     */
    public String decrypt(String encryptedKey) {
        if (encryptedKey == null || encryptedKey.isBlank()) return "";
        try {
            return new String(Base64.getDecoder().decode(encryptedKey), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("API Key 解码失败，可能不是 Base64 格式: {}", e.getMessage());
            return encryptedKey;
        }
    }

    /**
     * 脱敏 API Key：只保留前几位和后几位，中间用 **** 替代。
     * <p>
     * 如 "sk-abcd1234****wxyz" → "sk-abc****xyz"。
     *
     * @param rawKey 原始 API Key
     * @return 脱敏后的字符串
     */
    public String mask(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return "";
        if (rawKey.length() <= 8) return rawKey.substring(0, Math.min(2, rawKey.length())) + "****";
        return rawKey.substring(0, Math.min(6, rawKey.length() / 2)) + "****" + rawKey.substring(rawKey.length() - 3);
    }
}
