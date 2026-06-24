package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 知识库持久化服务。
 * <p>
 * 处理所有需要事务的数据库操作，与 {@link KnowledgeBaseUploadService} 分离，
 * 确保事务边界清晰——事务方法内不调用外部 API（LLM、S3 等）。
 */
@Service
public class KnowledgeBasePersistenceService {

    /**
     * 处理重复知识库。
     * <p>
     * 发现文件哈希已存在时，更新访问计数并返回已有记录信息。
     * 不会重新向量化——重复文件的向量数据应该已经存在。
     *
     * @param kb       已存在的知识库实体
     * @param fileHash 文件哈希
     * @return 与 {@link KnowledgeBaseUploadService#uploadKnowledgeBase} 格式一致的返回结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleDuplicateKnowledgeBase(KnowledgeBaseEntity kb, String fileHash) {
        return Map.of(); // TODO
    }

    /**
     * 保存新知识库元数据到数据库。
     *
     * @param file      上传的文件
     * @param name      知识库名称（可选）
     * @param category  分类（可选）
     * @param storageKey  MinIO 存储键
     * @param storageUrl  MinIO 访问 URL
     * @param fileHash  文件内容哈希
     * @return 保存后的知识库实体
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity saveKnowledgeBase(MultipartFile file, String name, String category,
                                                  String storageKey, String storageUrl, String fileHash) {
        return null; // TODO
    }

    /**
     * 更新知识库向量化状态为 PENDING。
     * <p>
     * 用于手动重新向量化时将状态重置为待处理。
     *
     * @param kbId 知识库 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateVectorStatusToPending(Long kbId) {
        // TODO
    }
}
