package interview.guide.infrastructure.file;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.xml.sax.ContentHandler;

import java.io.InputStream;

/**
 * 空操作的嵌入文档提取器。
 * <p>
 * 用于禁用 Tika 对嵌入资源（图片、附件等）的解析，只提取纯文本，
 * 避免 Tika 输出图片路径、临时文件等噪音。
 */
@Slf4j
public class NoOpEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {

    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
        String resourceName = metadata.get("resourceName");
        if (resourceName != null) {
            log.debug("Skipping embedded document: {}", resourceName);
        }
        return false;
    }

    @Override
    public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml) {
        // 空实现 — shouldParseEmbedded 返回 false，此方法不会被调用
    }
}
