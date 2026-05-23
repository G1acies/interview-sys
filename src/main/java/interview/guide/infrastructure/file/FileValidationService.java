package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class FileValidationService {

    public void validateFile(MultipartFile file, long maxSizeBytes, String fileTypeName) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    String.format("请选择要上传的%s文件", fileTypeName));
        }

        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制");
        }
    }
}
