package interview.guide.modules.interviewschedule.repository;

import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程数据访问接口。
 */
@Repository
public interface InterviewScheduleRepository extends JpaRepository<InterviewScheduleEntity, Long> {

    /**
     * 按面试时间范围查询（时间区间筛选，用于日历视图）。
     *
     * @param start 开始时间（含）
     * @param end   结束时间（含）
     */
    @Query("SELECT s FROM InterviewScheduleEntity s WHERE s.interviewTime BETWEEN :start AND :end ORDER BY s.interviewTime ASC")
    List<InterviewScheduleEntity> findByInterviewTimeBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 按状态查询。
     */
    List<InterviewScheduleEntity> findByStatusOrderByInterviewTimeAsc(InterviewStatus status);

    /**
     * 按状态 + 时间范围联合查询。
     */
    @Query("SELECT s FROM InterviewScheduleEntity s WHERE s.status = :status AND s.interviewTime BETWEEN :start AND :end ORDER BY s.interviewTime ASC")
    List<InterviewScheduleEntity> findByStatusAndInterviewTimeBetween(
        @Param("status") InterviewStatus status,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );

    /** 获取所有面试安排，按时间升序。 */
    List<InterviewScheduleEntity> findAllByOrderByInterviewTimeAsc();
}
