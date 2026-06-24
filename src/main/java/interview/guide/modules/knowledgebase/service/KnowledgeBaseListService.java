package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 知识库查询（列表/分类/搜索/统计/下载）服务。
 * <p>
 * 提供知识库的列表展示、分类管理、模糊搜索、统计信息和文件下载功能。
 */
@Service
public class KnowledgeBaseListService {

    /**
     * 获取知识库列表（支持状态过滤和排序）。
     *
     * @param vectorStatus 向量化状态过滤（null 表示不过滤）
     * @param sortBy       排序字段（"time"/"size"/"access"/"question"，null 默认按时间）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        return List.of(); // TODO
    }

    /** 获取所有知识库列表（默认排序）。 */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return List.of(); // TODO
    }

    /** 按向量化状态获取知识库列表。 */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return List.of(); // TODO
    }

    /** 根据 ID 获取知识库详情。 */
    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return Optional.empty(); // TODO
    }

    /** 根据 ID 获取知识库实体（内部使用）。 */
    public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
        return Optional.empty(); // TODO
    }

    /** 根据 ID 列表获取知识库名称列表。 */
    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        return List.of(); // TODO
    }

    // ========== 分类管理 ==========

    /** 获取所有已分配的分类名称。 */
    public List<String> getAllCategories() {
        return List.of(); // TODO
    }

    /** 根据分类获取知识库列表。 */
    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        return List.of(); // TODO
    }

    /** 更新知识库分类。 */
    @Transactional
    public void updateCategory(Long id, String category) {
        // TODO
    }

    // ========== 搜索 ==========

    /** 按关键词搜索知识库（名称或原始文件名）。 */
    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        return List.of(); // TODO
    }

    // ========== 排序 ==========

    /** 按指定字段排序获取知识库列表。 */
    public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
        return List.of(); // TODO
    }

    // ========== 统计 ==========

    /** 获取知识库统计信息。 */
    public KnowledgeBaseStatsDTO getStatistics() {
        return null; // TODO
    }

    // ========== 下载 ==========

    /** 下载知识库文件（返回字节数组）。 */
    public byte[] downloadFile(Long id) {
        return new byte[0]; // TODO
    }

    /** 获取知识库文件信息（用于下载时的文件名和 Content-Type）。 */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return null; // TODO
    }
}
