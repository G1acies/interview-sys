package interview.guide.infrastructure.export;

/*
 * iText 8 核心对象说明：
 *
 *   ByteArrayOutputStream  ─→  内存输出流，PDF 字节写在这里，不落盘
 *   PdfWriter              ─→  底层 PDF 写入器，负责把内容序列化为 PDF 二进制
 *   PdfDocument            ─→  底层 PDF 文档结构（页、交叉引用表等）
 *   Document               ─→  高层排版 API（加段落、改字体、设颜色等）
 *
 *   执行顺序：
 *   ① new PdfWriter(baos)               — 输出目标
 *   ② new PdfDocument(writer)           — 底层文档绑定 writer
 *   ③ new Document(pdfDoc)              — 高层排版绑定底层文档
 *   ④ document.add(Paragraph...)        — 用链式调用排版
 *   ⑤ document.close()                  — 必须在 finally 里调用，否则 PDF 损坏
 *   ⑥ return baos.toByteArray()         — 拿到完整字节数组
 */

import com.itextpdf.io.font.PdfEncodings;       // IDENTITY_H：按 Unicode 字符自身编码，不乱码
import com.itextpdf.kernel.colors.DeviceRgb;    // 颜色表示为 new DeviceRgb(R, G, B)
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;   // 从 TTF 字节创建字体
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;                // 排版上下文：字体、间距、对齐
import com.itextpdf.layout.element.Paragraph;        // 一段文字，可以链式设置字号/粗细/颜色
import com.itextpdf.layout.properties.TextAlignment; // CENTER / LEFT / RIGHT
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

/**
 * PDF 导出服务 — 将简历分析结果导出为格式化的 PDF 报告。
 *
 * <h3>iText 排版模型</h3>
 * iText 用 "流式布局"（类似 HTML 行内块）：
 * <ul>
 *   <li>{@link Document} 是整个排版画布，{@code add()} 的内容从上到下依次排列</li>
 *   <li>{@link Paragraph} 是最基础的块级元素，自带换行和默认字号</li>
 *   <li>所有属性（字号、颜色、加粗、对齐）都是链式调用，返回对象自身</li>
 * </ul>
 *
 * <h3>中文显示的必要条件</h3>
 * iText 内置的 Helvetica / Times 字体不支持中文，所以：
 * <ol>
 *   <li>必须加载一个 TTF 中文源 — 本项目用朱雀仿宋</li>
 *   <li>必须用 FORCE_EMBEDDED 把字体塞进 PDF — 否则别人电脑打开就是空白方框</li>
 *   <li>编码必须用 IDENTITY_H — 不经过字符映射，直接用 Unicode 码点</li>
 *   <li>调用 {@code document.setFont(font)} 设为全局字体</li>
 * </ol>
 */
@Slf4j
@Service
public class PdfExportService {

    /** 日期格式化，如 2026-06-22 16:30:00 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 标题栏颜色：天蓝色 */
    private static final DeviceRgb COLOR_TITLE = new DeviceRgb(41, 128, 185);
    /** 章节标题颜色：深灰 */
    private static final DeviceRgb COLOR_SECTION = new DeviceRgb(52, 73, 94);

    private final ObjectMapper objectMapper;

    /** 构造器注入 — Spring Boot 自动创建 Jackson 3.x ObjectMapper Bean */
    public PdfExportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 导出简历分析报告为 PDF，整个流程分三个步骤。
     *
     * <h3>① 创建 PDF 管道</h3>
     * {@code baos ← PdfWriter ← PdfDocument ← Document}
     * <p>
     * 为什么不直接写文件？因为最终要作为 HTTP 响应的字节流返回，
     * 所以全程在内存 {@link ByteArrayOutputStream} 里完成。
     *
     * <h3>② 排版内容</h3>
     * 按固定结构依次写入六个 Part：
     * <ol>
     *   <li>大标题"简历分析报告"</li>
     *   <li>基本信息（文件名、时间、大小、状态）</li>
     *   <li>个人信息（姓名、电话、邮箱）— 从 analysisResult JSON 解析</li>
     *   <li>专业技能 / 工作经历 / 教育背景</li>
     *   <li>简历原文 — 字号缩小到 10pt，保持卷面紧凑</li>
     * </ol>
     *
     * <h3>③ 关闭 + 返回字节数组</h3>
     * {@code document.close()} 会触发 iText 把所有内存缓冲写入底层的 {@link PdfWriter}，
     * 最终落到 {@link ByteArrayOutputStream}。不管成功还是失败都要 close，否则 PDF 损坏。
     *
     * @param resume 简历实体，至少需要 fileName / rawText / analysisResult 三个字段
     * @return 完整 PDF 字节数组，可直接写入 HTTP 响应或磁盘
     */
    public byte[] exportResume(ResumeEntity resume) {
        // ── ① 创建 PDF 管道 ──
        ByteArrayOutputStream baos = new ByteArrayOutputStream();  // 内存输出流
        PdfWriter writer = new PdfWriter(baos);                    // 绑定输出目标
        PdfDocument pdfDoc = new PdfDocument(writer);              // 底层 PDF 结构
        Document document = new Document(pdfDoc);                  // 高层排版 API

        try {
            // ── ② 设置全局中文字体 ──
            // setFont 对 document 生效后，后续 add 的段落都会默认用这个字体
            PdfFont font = loadChineseFont();
            document.setFont(font);

            // ── Part 1：大标题 ──
            // Paragraph 默认左对齐、常规字重、12pt字号
            // 通过链式调用 setFontSize(24).setBold() 覆盖默认值
            // TextAlignment.CENTER 让标题居中
            document.add(new Paragraph("简历分析报告")
                    .setFontSize(24)           // 24pt ≈ 二号字
                    .setBold()                  // 加粗
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(COLOR_TITLE));

            // "\n" 是一个只有换行的段落，用来增加垂直间距
            document.add(new Paragraph("\n"));

            // ── Part 2：基本信息 ──
            // 这些是 ResumeEntity 直接存的字段，不依赖 AI 分析
            document.add(sectionTitle("基本信息"));
            document.add(new Paragraph("文件名: " + safe(resume.getFileName())));
            document.add(new Paragraph("上传时间: "
                    + (resume.getCreatedAt() != null
                    ? DATE_FMT.format(resume.getCreatedAt()) : "未知")));
            document.add(new Paragraph("文件大小: " + formatFileSize(resume.getFileSize())));
            document.add(new Paragraph("解析状态: " + statusText(resume.getStatus())));

            // ── Part 3：AI 分析结果 ──
            // analysisResult 是 AI 返回的 JSON 字符串，用 Jackson 解析为树形结构
            // 如果为空（AI 没跑或失败）则跳过此 Part，不影响基本信息导出
            if (resume.getAnalysisResult() != null && !resume.getAnalysisResult().isBlank()) {
                try {
                    // readTree 把 JSON 字符串解析为树形节点
                    // 不反序列化为强类型对象，因为字段可能不完整
                    JsonNode root = objectMapper.readTree(resume.getAnalysisResult());

                    // 个人信息（如果 AI 提取到了姓名）
                    // has("name") 先检查键是否存在，isNull() 再检查值是否为空
                    if (root.has("name") && !root.get("name").isNull()) {
                        document.add(new Paragraph("\n"));
                        document.add(sectionTitle("个人信息"));
                        document.add(new Paragraph("姓名: " + safe(root.get("name").asText())));
                        if (root.has("phone") && !root.get("phone").isNull())
                            document.add(new Paragraph("电话: " + safe(root.get("phone").asText())));
                        if (root.has("email") && !root.get("email").isNull())
                            document.add(new Paragraph("邮箱: " + safe(root.get("email").asText())));
                    }

                    // 技能列表 — JSON 数组，遍历拼接
                    if (root.has("skills") && root.get("skills").size() > 0) {
                        document.add(new Paragraph("\n"));
                        document.add(sectionTitle("专业技能"));
                        StringBuilder sb = new StringBuilder();
                        root.get("skills").forEach(s ->
                                sb.append("• ").append(safe(s.asText())).append("\n"));
                        document.add(new Paragraph(sb.toString().trim()));
                    }

                    // 工作经历 — JSON 数组，每条用 | 分隔成一行
                    if (root.has("experience") && root.get("experience").size() > 0) {
                        document.add(new Paragraph("\n"));
                        document.add(sectionTitle("工作经历"));
                        for (JsonNode exp : root.get("experience")) {
                            // 防御性写法：每个字段都检查 has() 是否存在
                            document.add(new Paragraph(String.format("%s  |  %s  |  %s",
                                    safe(exp.has("company") ? exp.get("company").asText() : ""),
                                    safe(exp.has("role") ? exp.get("role").asText() : ""),
                                    safe(exp.has("duration") ? exp.get("duration").asText() : ""))));
                        }
                    }

                    // 教育背景
                    if (root.has("education") && root.get("education").size() > 0) {
                        document.add(new Paragraph("\n"));
                        document.add(sectionTitle("教育背景"));
                        for (JsonNode edu : root.get("education")) {
                            document.add(new Paragraph(String.format("%s  |  %s  |  %s",
                                    safe(edu.has("school") ? edu.get("school").asText() : ""),
                                    safe(edu.has("degree") ? edu.get("degree").asText() : ""),
                                    safe(edu.has("major") ? edu.get("major").asText() : ""))));
                        }
                    }
                } catch (Exception e) {
                    // JSON 解析失败时在 PDF 里注明，不阻断整体导出
                    log.warn("解析 analysisResult JSON 失败: id={}", resume.getId(), e);
                    document.add(new Paragraph("\n（AI 分析结果解析失败）"));
                }
            }

            // ── Part 4：简历原文 ──
            // rawText 是 Tika 提取的纯文本，通常较长，用 10pt 小字号节省版面
            if (resume.getRawText() != null && !resume.getRawText().isBlank()) {
                document.add(new Paragraph("\n"));
                document.add(sectionTitle("简历原文"));
                document.add(new Paragraph(safe(resume.getRawText())).setFontSize(10));
            }

            // ── ③ 关闭文档 ──
            // document.close() 触发 iText 完成所有页面的排版和编码工作，
            // 将最终 PDF 字节序列化到底层的 PdfWriter → ByteArrayOutputStream
            document.close();

        } catch (Exception e) {
            // 排版失败也必须 close，否则 PdfWriter 残留的缓冲数据可能损坏内存流
            document.close();
            log.error("生成 PDF 失败: id={}, error={}", resume.getId(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED,
                    "PDF 生成失败: " + e.getMessage());
        }

        // baos.toByteArray() 只在 close() 之后才能得到完整 PDF
        return baos.toByteArray();
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 从 classpath 加载中文字体并创建 iText 字体对象。
     *
     * <h3>三个关键参数</h3>
     * <table>
     *   <tr><th>参数</th><th>作用</th></tr>
     *   <tr><td>fontBytes</td><td>TTF 文件的完整字节，从 classpath 的 fonts/ 目录读取</td></tr>
     *   <tr><td>IDENTITY_H</td><td>不经过字符编码映射，直接用 Unicode 码点定位字形 — 中文必须用这个</td></tr>
     *   <tr><td>FORCE_EMBEDDED</td><td>把字体数据完整嵌入 PDF — 否则接收方电脑没装这个字体就会看到空白方框</td></tr>
     * </table>
     *
     * <h3>为什么不用系统字体</h3>
     * 如果从操作系统路径加载（如 {@code C:\Windows\Fonts\simsun.ttc}），
     * 部署到 Linux 服务器就会找不到文件。classpath 内嵌是跨平台的方案。
     */
    private PdfFont loadChineseFont() {
        try (var fontStream = getClass().getClassLoader()
                .getResourceAsStream("fonts/ZhuqueFangsong-Regular.ttf")) {
            if (fontStream == null) {
                throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED,
                        "字体文件缺失，请联系管理员");
            }
            byte[] fontBytes = fontStream.readAllBytes();
            return PdfFontFactory.createFont(fontBytes,
                    PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
        } catch (BusinessException e) {
            throw e;  // 字体缺失是业务异常，原样抛出，不二次包装
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED,
                    "加载中文字体失败: " + e.getMessage());
        }
    }

    /** 创建一个带样式的章节标题 Paragraph：14pt 加粗深灰色 */
    private Paragraph sectionTitle(String text) {
        return new Paragraph(text)
                .setFontSize(14)
                .setBold()
                .setFontColor(COLOR_SECTION);
    }

    /**
     * 文本安全处理。
     * <ul>
     *   <li>null → 空字符串（防止 NPE）</li>
     *   <li>移除不可见控制字符和 emoji（{\code \p{So},\p{Cs}}），这些字符在大部分字体里没有字形，会显示为豆腐块</li>
     * </ul>
     */
    private String safe(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\p{So}\\p{Cs}]", "").trim();
    }

    /** 文件大小人性化显示：B → KB → MB */
    private String formatFileSize(Long bytes) {
        if (bytes == null) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    /** 状态枚举 → 中文显示 */
    private String statusText(String status) {
        return switch (status) {
            case "PENDING" -> "待处理";
            case "PARSED" -> "已解析";
            case "COMPLETED" -> "分析完成";
            case "FAILED" -> "失败";
            default -> status;
        };
    }

    // ==================== 面试报告导出 ====================

    /**
     * 导出面试评估报告为 PDF。
     * <p>
     * 排版结构：
     * <ol>
     *   <li>标题"模拟面试报告"</li>
     *   <li>基本信息（会话ID、方向、难度、状态、时间）</li>
     *   <li>总分（大字显示）</li>
     *   <li>总体评价</li>
     *   <li>优势列表</li>
     *   <li>改进建议</li>
     * </ol>
     *
     * @param session 面试会话实体
     * @return 完整 PDF 字节数组
     */
    public byte[] exportInterviewReport(InterviewSessionEntity session) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);

        try {
            PdfFont font = loadChineseFont();
            document.setFont(font);

            // 大标题
            document.add(new Paragraph("模拟面试报告")
                    .setFontSize(24).setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(COLOR_TITLE));
            document.add(new Paragraph("\n"));

            // 基本信息
            document.add(sectionTitle("基本信息"));
            document.add(new Paragraph("会话ID: " + safe(session.getSessionId())));
            document.add(new Paragraph("面试方向: " + safe(session.getSkillId())));
            document.add(new Paragraph("难度级别: " + safe(session.getDifficulty())));
            document.add(new Paragraph("题目数量: " + (
                    session.getTotalQuestions() != null ? session.getTotalQuestions() : 0)));
            document.add(new Paragraph("状态: " + (
                    session.getStatus() != null ? session.getStatus().name() : "未知")));
            document.add(new Paragraph("创建时间: " + (
                    session.getCreatedAt() != null
                            ? DATE_FMT.format(session.getCreatedAt()) : "未知")));
            if (session.getCompletedAt() != null) {
                document.add(new Paragraph("完成时间: " + DATE_FMT.format(session.getCompletedAt())));
            }

            // 总分
            document.add(new Paragraph("\n"));
            document.add(sectionTitle("评估得分"));
            document.add(new Paragraph(String.format("%d / 100",
                    session.getOverallScore() != null ? session.getOverallScore() : 0))
                    .setFontSize(36).setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(new DeviceRgb(39, 174, 96)));

            // 总体评价
            if (session.getOverallFeedback() != null && !session.getOverallFeedback().isBlank()) {
                document.add(new Paragraph("\n"));
                document.add(sectionTitle("总体评价"));
                document.add(new Paragraph(safe(session.getOverallFeedback())));
            }

            // 优势
            if (session.getStrengthsJson() != null && !session.getStrengthsJson().isBlank()) {
                document.add(new Paragraph("\n"));
                document.add(sectionTitle("优势"));
                try {
                    var strengths = objectMapper.readTree(session.getStrengthsJson());
                    StringBuilder sb = new StringBuilder();
                    strengths.forEach(s -> sb.append("• ").append(safe(s.asText())).append("\n"));
                    String text = sb.toString().trim();
                    if (!text.isEmpty()) {
                        document.add(new Paragraph(text));
                    }
                } catch (Exception e) {
                    log.warn("解析 strengthsJson 失败", e);
                }
            }

            // 改进建议
            if (session.getImprovementsJson() != null && !session.getImprovementsJson().isBlank()) {
                document.add(new Paragraph("\n"));
                document.add(sectionTitle("改进建议"));
                try {
                    var improvements = objectMapper.readTree(session.getImprovementsJson());
                    StringBuilder sb = new StringBuilder();
                    improvements.forEach(s -> sb.append("• ").append(safe(s.asText())).append("\n"));
                    String text = sb.toString().trim();
                    if (!text.isEmpty()) {
                        document.add(new Paragraph(text));
                    }
                } catch (Exception e) {
                    log.warn("解析 improvementsJson 失败", e);
                }
            }

            document.close();
        } catch (Exception e) {
            document.close();
            log.error("生成面试报告 PDF 失败: sessionId={}, error={}",
                    session.getSessionId(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED,
                    "PDF 生成失败: " + e.getMessage());
        }

        return baos.toByteArray();
    }
}
