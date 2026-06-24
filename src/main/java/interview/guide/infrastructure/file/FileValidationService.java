package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.function.Predicate;

/**
 * 文件校验服务 — 在上传前做安全检查。
 * <p>
 * 校验项：文件是否为空、文件大小、MIME 类型白名单。
 */
@Slf4j
@Service
public class FileValidationService {

    /**
     * 校验上传文件的基本属性。
     *
     * @param file         前端传来的 MultipartFile（Spring 已自动解析 multipart/form-data）
     * @param maxSizeBytes 最大允许的字节数
     * @param fileTypeName 文件类型的中文名，用于错误提示（如"简历"、"知识库文件"）
     */
    public void validateFile(MultipartFile file, long maxSizeBytes, String fileTypeName) {
        // 1. 空文件检查 — 用户可能没选文件就点了上传
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    String.format("请选择要上传的%s文件", fileTypeName));
        }

        // 2. 大小限制检查 — 防止单文件过大撑爆存储
        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制");
        }
    }

    /**
     * 验证文件 MIME 类型是否在允许范围内。
     * <p>
     * 先检查 MIME 类型，如果不匹配再检查文件扩展名（因为浏览器有时会传 application/octet-stream）。
     *
     * @param contentType     MIME 类型
     * @param fileName        原始文件名（用于扩展名兜底检查）
     * @param mimeTypeChecker MIME 类型匹配函数
     * @param extensionChecker 扩展名匹配函数
     * @param errorMessage    全部不匹配时的提示语
     */
    public void validateContentType(String contentType, String fileName,
                                   Predicate<String> mimeTypeChecker,
                                   Predicate<String> extensionChecker,
                                   String errorMessage) {
        if (mimeTypeChecker.test(contentType)) {
            return;
        }
        if (fileName != null && extensionChecker.test(fileName)) {
            return;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            errorMessage != null ? errorMessage : "不支持的文件类型: " + contentType);
    }

    /**
     * 判断 MIME 类型是否为知识库支持的格式。
     * <p>
     * 支持：PDF、Word、纯文本、Markdown、RTF。
     */
    public boolean isKnowledgeBaseMimeType(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.contains("pdf")
            || lower.contains("msword")
            || lower.contains("wordprocessingml")
            || lower.contains("text/plain")
            || lower.contains("text/markdown")
            || lower.contains("text/x-markdown")
            || lower.contains("application/rtf");
    }

    /**
     * 判断文件扩展名是否为 Markdown 格式。
     */
    public boolean isMarkdownExtension(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".md")
            || lower.endsWith(".markdown")
            || lower.endsWith(".mdown");
    }
}
