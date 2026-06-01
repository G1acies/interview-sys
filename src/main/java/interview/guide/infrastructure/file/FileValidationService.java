package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件校验服务 — 在上传前做安全检查。
 * <p>
 * 当前校验项：文件是否为空、文件大小是否超限。
 * 后续可扩展 MIME 类型白名单校验（如只允许 PDF/DOCX）。
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
}
