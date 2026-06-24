package interview.guide.modules.knowledgebase.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库解析服务。
 * <p>
 * 委托给 {@link interview.guide.infrastructure.file.DocumentParseService} 执行实际的文档解析。
 * 提供针对知识库场景的便捷方法封装。
 */
@Service
public class KnowledgeBaseParseService {

    /**
     * 解析上传的知识库文件，提取文本内容。
     *
     * @param file 上传的文件（PDF/DOCX/TXT/MD 等）
     * @return 提取的纯文本内容
     */
    public String parseContent(MultipartFile file) {
        return ""; // TODO
    }

    /**
     * 解析字节数组形式的文件内容。
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 提取的纯文本内容
     */
    public String parseContent(byte[] fileBytes, String fileName) {
        return ""; // TODO
    }

    /**
     * 从 MinIO 存储下载文件并解析内容。
     *
     * @param storageKey       存储键
     * @param originalFilename 原始文件名
     * @return 提取的纯文本内容
     */
    public String downloadAndParseContent(String storageKey, String originalFilename) {
        return ""; // TODO
    }

    /**
     * 检测文件的 MIME 类型。
     *
     * @param file 上传的文件
     * @return MIME 类型字符串
     */
    public String detectContentType(MultipartFile file) {
        return ""; // TODO
    }
}
