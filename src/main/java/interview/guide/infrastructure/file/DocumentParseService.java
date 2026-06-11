package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 文档解析服务 — 使用 Apache Tika 从 PDF/DOCX/TXT/MD 中提取纯文本。
 * <p>
 * 核心方法 {@link #parseContent(MultipartFile)} 接收上传文件，返回清洗后的文本。
 * 结果中的图片名、文件路径、控制字符等噪音已被 {@link TextCleaningService} 过滤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseService {

    private static final int MAX_TEXT_LENGTH = 5 * 1024 * 1024; // 5MB 上限

    private final TextCleaningService textCleaningService;

    /**
     * 解析上传的 MultipartFile，提取清洗后的文本。
     *
     * @param file 上传的文件（PDF/DOCX/DOC/TXT/MD）
     * @return 纯文本内容
     * @throws BusinessException 解析失败时抛 RESUME_PARSE_FAILED
     */
    public String parseContent(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("开始解析文件: {}", fileName);

        if (file.isEmpty() || file.getSize() == 0) {
            log.warn("文件为空: {}", fileName);
            return "";
        }

        try (InputStream inputStream = file.getInputStream()) {
            String content = parseContent(inputStream);
            String cleaned = textCleaningService.cleanText(content);
            log.info("文件解析成功，提取文本长度: {} 字符", cleaned.length());
            return cleaned;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("文件解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 解析字节数组形式的文件内容。
     * 适用于从 MinIO 下载后解析的场景。
     */
    public String parseContent(byte[] fileBytes, String fileName) {
        log.info("开始解析文件（字节数组）: {}", fileName);

        if (fileBytes == null || fileBytes.length == 0) {
            return "";
        }

        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            String content = parseContent(inputStream);
            String cleaned = textCleaningService.cleanText(content);
            log.info("文件解析成功，提取文本长度: {} 字符", cleaned.length());
            return cleaned;
        } catch (Exception e) {
            log.error("文件解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 核心解析方法。
     * <p>
     * 配置要点：
     * <ol>
     *   <li>AutoDetectParser — 自动检测文件格式（PDF/DOCX/TXT）</li>
     *   <li>BodyContentHandler — 只提取正文，不包含 XML 包装</li>
     *   <li>NoOpEmbeddedDocumentExtractor — 禁用图片/附件提取</li>
     *   <li>PDFParserConfig — 关闭图片提取，按位置排序文本</li>
     * </ol>
     */
    private String parseContent(InputStream inputStream) throws IOException, TikaException, SAXException {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        // 显式指定 parser 到 context（增强健壮性）
        context.set(Parser.class, parser);

        // 禁用嵌入文档解析（关键：避免提取图片引用）
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

        // PDF 专用配置：关闭图片，按坐标排序（改善多栏布局）
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true);
        context.set(PDFParserConfig.class, pdfConfig);

        parser.parse(inputStream, handler, metadata, context);
        return handler.toString();
    }

    /**
     * 从 MinIO 下载文件并解析。
     */
    public String downloadAndParseContent(FileStorageService storageService, String storageKey, String originalFilename) {
        try {
            byte[] fileBytes = storageService.downloadFile(storageKey);
            return parseContent(fileBytes, originalFilename);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("下载并解析文件失败: storageKey={}, error={}", storageKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "下载并解析文件失败: " + e.getMessage());
        }
    }
}
