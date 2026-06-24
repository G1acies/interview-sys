package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 知识库上传服务。
 * <p>
 * 处理知识库文件上传的完整业务流程：
 * <ol>
 *   <li>文件验证 — 大小、类型（Tika 检测 MIME）</li>
 *   <li>内容去重 — 计算 SHA-256 哈希，查重</li>
 *   <li>文档解析 — Tika 提取纯文本</li>
 *   <li>文件存储 — 上传到 MinIO（S3 协议）</li>
 *   <li>元数据入库 — 保存数据库记录（状态 = PENDING）</li>
 *   <li>异步向量化 — 发送任务到 Redis Stream</li>
 *   <li>手动重试 — 重新下载文件并重新发送向量化任务</li>
 * </ol>
 */
@Service
public class KnowledgeBaseUploadService {

    /**
     * 上传知识库文件。
     * <p>
     * 返回结果包含 knowledgeBase 信息和 duplicate 标记，
     * 前端可根据 duplicate 判断是否为重复上传。
     *
     * @param file     上传的文件
     * @param name     知识库名称（可选）
     * @param category 分类（可选）
     * @return 上传结果，含 id、名称、存储信息和是否重复标记
     */
    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category) {
        return Map.of(); // TODO
    }

    /**
     * 重新向量化知识库（手动重试）。
     * <p>
     * 从 MinIO 重新下载文件 → 解析内容 → 发送向量化任务。
     * 用于向量化失败后的手动重试。
     *
     * @param kbId 知识库 ID
     */
    public void revectorize(Long kbId) {
        // TODO
    }
}
