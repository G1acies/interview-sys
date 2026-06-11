package interview.guide.infrastructure.file;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 文本清理服务 — 去除 Tika 解析结果中的噪音。
 * <p>
 * Tika 从 PDF/DOCX 提取文本时会混入很多无用内容：
 * <ul>
 *   <li>图片文件名（image001.png 等）</li>
 *   <li>图片链接、文件路径</li>
 *   <li>控制字符、HTML 片段</li>
 *   <li>大量空行、行尾空格</li>
 * </ul>
 * 本服务负责把这些噪音去掉，输出干净的文本供 AI 分析。
 */
@Service
public class TextCleaningService {

    private static final Pattern IMAGE_FILENAME_LINE =
            Pattern.compile("(?m)^image\\d+\\.(png|jpe?g|gif|bmp|webp)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern IMAGE_URL =
            Pattern.compile("https?://\\S+?\\.(png|jpe?g|gif|bmp|webp)(\\?\\S*)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern FILE_URL =
            Pattern.compile("file:(//)?\\S+", Pattern.CASE_INSENSITIVE);

    private static final Pattern SEPARATOR_LINE =
            Pattern.compile("(?m)^\\s*[-_*=]{3,}\\s*$");

    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");

    private static final Pattern HTML_TAGS =
            Pattern.compile("<[^>]+>");

    /**
     * 清理文本：去噪音 → 去控制字符 → 归一化格式。
     */
    public String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String t = text;

        // 第一层：语义去噪
        t = CONTROL_CHARS.matcher(t).replaceAll("");
        t = IMAGE_FILENAME_LINE.matcher(t).replaceAll("");
        t = IMAGE_URL.matcher(t).replaceAll("");
        t = FILE_URL.matcher(t).replaceAll("");
        t = SEPARATOR_LINE.matcher(t).replaceAll("");

        // 第二层：格式规范化
        t = t.replace("\r\n", "\n").replace("\r", "\n");
        t = t.replaceAll("(?m)[ \t]+$", "");     // 去行尾空格
        t = t.replaceAll("\\n{3,}", "\n\n");      // 压缩连续空行

        return t.strip();
    }

    public String stripHtml(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return HTML_TAGS.matcher(text).replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
