package interview.guide.infrastructure.file;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件内容类型检测服务。
 * <p>
 * 使用 Apache Tika 的魔数（magic bytes）检测机制精确判断 MIME 类型，
 * 比 HTTP 请求头中的 Content-Type 更可靠（浏览器有时会传 application/octet-stream）。
 * <p>
 * 知识库上传时先用此服务检测真实文件类型，再决定是否允许上传。
 */
@Slf4j
@Service
public class ContentTypeDetectionService {

    private final Tika tika;

    public ContentTypeDetectionService() {
        this.tika = new Tika();
    }

    /**
     * 检测上传文件的 MIME 类型。
     * <p>
     * 优先读取文件头部的魔数字节判断，比 HTTP Content-Type 头部准确。
     * 如果读流失败则降级使用 {@link MultipartFile#getContentType()}。
     *
     * @param file 上传的文件
     * @return 检测到的 MIME 类型，如 "application/pdf"
     */
    public String detectContentType(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return tika.detect(inputStream, file.getOriginalFilename());
        } catch (IOException e) {
            log.warn("无法检测文件类型，使用 Content-Type 头部: {}", e.getMessage());
            return file.getContentType();
        }
    }

    /**
     * 检测输入流的 MIME 类型。
     *
     * @param inputStream 文件输入流
     * @param fileName 文件名（可选，辅助 Tika 判断）
     * @return MIME 类型字符串，检测失败返回 "application/octet-stream"
     */
    public String detectContentType(InputStream inputStream, String fileName) {
        try {
            return tika.detect(inputStream, fileName);
        } catch (IOException e) {
            log.warn("无法检测文件类型: {}", e.getMessage());
            return "application/octet-stream";
        }
    }

    /**
     * 检测字节数组的 MIME 类型。
     *
     * @param data     文件字节数组
     * @param fileName 文件名（可选，辅助 Tika 判断）
     * @return MIME 类型字符串
     */
    public String detectContentType(byte[] data, String fileName) {
        return tika.detect(data, fileName);
    }

    // ========== 快捷判断方法 ==========

    /**
     * 判断是否为 PDF 文件。
     */
    public boolean isPdf(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("pdf");
    }

    /**
     * 判断是否为 Word 文档（DOC/DOCX）。
     */
    public boolean isWordDocument(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.contains("msword") || lower.contains("wordprocessingml");
    }

    /**
     * 判断是否为纯文本文件。
     */
    public boolean isPlainText(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("text/");
    }

    /**
     * 判断是否为 Markdown 文件。
     * <p>
     * 优先检查 MIME 类型，然后降级检查文件扩展名。
     *
     * @param contentType MIME 类型（可为 null）
     * @param fileName    文件名（可为 null）
     */
    public boolean isMarkdown(String contentType, String fileName) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            if (lower.contains("markdown") || lower.contains("x-markdown")) {
                return true;
            }
        }
        if (fileName != null) {
            String lowerName = fileName.toLowerCase();
            return lowerName.endsWith(".md") ||
                   lowerName.endsWith(".markdown") ||
                   lowerName.endsWith(".mdown");
        }
        return false;
    }
}
