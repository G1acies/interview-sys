package interview.guide.modules.interviewschedule.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程 CRUD 服务。
 * <p>
 * 提供面试安排的增删改查和状态变更功能。
 * 不含 AI 解析（由 {@link InterviewParseService} 负责）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    private final InterviewScheduleRepository repository;

    // ==================================================================
    // Create
    // ==================================================================

    /**
     * 创建面试安排。
     * <p>
     * 从前端传入的请求中提取字段，构建 Entity 并持久化。
     * 状态默认 {@link InterviewStatus#PENDING}。
     *
     * @param request 创建请求（公司、职位、时间必填）
     * @return 新建的面试安排 DTO
     */
    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request) {
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        entity.setCompanyName(request.companyName());
        entity.setPosition(request.position());
        entity.setInterviewTime(request.interviewTime());
        entity.setInterviewType(request.interviewType());
        entity.setMeetingLink(request.meetingLink());
        entity.setRoundNumber(request.roundNumber());
        entity.setInterviewer(request.interviewer());
        entity.setNotes(request.notes());
        // 状态默认 PENDING，由 Entity 字段默认值保证

        entity = repository.save(entity);
        log.info("创建面试安排: id={}, company={}, position={}", entity.getId(), entity.getCompanyName(), entity.getPosition());
        return toDTO(entity);
    }

    // ==================================================================
    // Read
    // ==================================================================

    /**
     * 获取单个面试安排详情。
     *
     * @param id 面试安排 ID
     * @return 面试安排 DTO
     * @throws BusinessException 不存在时抛出 NOT_FOUND
     */
    public InterviewScheduleDTO getById(Long id) {
        return repository.findById(id)
            .map(this::toDTO)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试安排不存在"));
    }

    /**
     * 获取面试安排列表。
     * <p>
     * 支持可选的状态过滤和时间范围筛选：
     * <ul>
     *   <li>仅 status → 按状态过滤</li>
     *   <li>仅 start/end → 按时间区间过滤</li>
     *   <li>status + start/end → 联合过滤</li>
     *   <li>全不传 → 返回全部，按面试时间升序</li>
     * </ul>
     *
     * @param status 状态过滤（可选）
     * @param start  开始时间（可选）
     * @param end    结束时间（可选）
     * @return 面试安排列表
     */
    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        InterviewStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = InterviewStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的面试状态: " + status);
            }
        }

        boolean hasStatus = statusEnum != null;
        boolean hasTimeRange = start != null && end != null;

        List<InterviewScheduleEntity> entities;

        if (hasStatus && hasTimeRange) {
            entities = repository.findByStatusAndInterviewTimeBetween(statusEnum, start, end);
        } else if (hasStatus) {
            entities = repository.findByStatusOrderByInterviewTimeAsc(statusEnum);
        } else if (hasTimeRange) {
            entities = repository.findByInterviewTimeBetween(start, end);
        } else {
            entities = repository.findAllByOrderByInterviewTimeAsc();
        }

        return entities.stream().map(this::toDTO).toList();
    }

    // ==================================================================
    // Update
    // ==================================================================

    /**
     * 全量更新面试安排。
     * <p>
     * 先查找实体，不存在则抛异常；存在则覆盖所有可编辑字段。
     * 注意：状态应通过 {@link #updateStatus} 单独更新，此处不改。
     *
     * @param id      面试安排 ID
     * @param request 更新后的数据
     * @return 更新后的面试安排 DTO
     */
    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试安排不存在"));

        entity.setCompanyName(request.companyName());
        entity.setPosition(request.position());
        entity.setInterviewTime(request.interviewTime());
        entity.setInterviewType(request.interviewType());
        entity.setMeetingLink(request.meetingLink());
        entity.setRoundNumber(request.roundNumber());
        entity.setInterviewer(request.interviewer());
        entity.setNotes(request.notes());

        entity = repository.save(entity);
        log.info("更新面试安排: id={}", entity.getId());
        return toDTO(entity);
    }

    /**
     * 更新面试状态。
     * <p>
     * 与全量更新分离，前端只需要传 status 即可更新状态。
     * 支持 PATCH 语义。
     *
     * @param id     面试安排 ID
     * @param status 新状态
     * @return 更新后的面试安排 DTO
     */
    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
        InterviewScheduleEntity entity = repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试安排不存在"));
        entity.setStatus(status);
        entity = repository.save(entity);
        log.info("更新面试状态: id={}, status={}", id, status);
        return toDTO(entity);
    }

    // ==================================================================
    // Delete
    // ==================================================================

    /**
     * 删除面试安排。
     *
     * @param id 面试安排 ID
     */
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试安排不存在");
        }
        repository.deleteById(id);
        log.info("删除面试安排: id={}", id);
    }

    // ==================================================================
    // 内部方法
    // ==================================================================

    /** Entity → DTO 转换。 */
    private InterviewScheduleDTO toDTO(InterviewScheduleEntity e) {
        return new InterviewScheduleDTO(
            e.getId(),
            e.getCompanyName(),
            e.getPosition(),
            e.getInterviewTime(),
            e.getInterviewType(),
            e.getMeetingLink(),
            e.getRoundNumber(),
            e.getInterviewer(),
            e.getNotes(),
            e.getStatus(),
            e.getCreatedAt(),
            e.getUpdatedAt()
        );
    }
}
