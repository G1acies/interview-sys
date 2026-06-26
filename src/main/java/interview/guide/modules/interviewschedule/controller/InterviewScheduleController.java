package interview.guide.modules.interviewschedule.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.model.ParseRequest;
import interview.guide.modules.interviewschedule.model.ParseResponse;
import interview.guide.modules.interviewschedule.service.InterviewParseService;
import interview.guide.modules.interviewschedule.service.InterviewScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程控制器。
 * <p>
 * ┌────────────────────────────────────────────────────────────┐
 * │ 端点总览                                                    │
 * ├────────────────────────────────────────────────────────────┤
 * │ POST   /api/interview-schedule/parse     AI 解析邀约文本    │
 * │ POST   /api/interview-schedule           创建面试安排       │
 * │ GET    /api/interview-schedule           列表（支持过滤）    │
 * │ GET    /api/interview-schedule/{id}      详情              │
 * │ PUT    /api/interview-schedule/{id}      全量更新           │
 * │ DELETE /api/interview-schedule/{id}      删除              │
 * │ PATCH  /api/interview-schedule/{id}/status 更新状态        │
 * └────────────────────────────────────────────────────────────┘
 */
@Slf4j
@RestController
@RequestMapping("/api/interview-schedule")
@RequiredArgsConstructor
public class InterviewScheduleController {

    private final InterviewScheduleService scheduleService;
    private final InterviewParseService parseService;

    // ==================================================================
    // 业务操作（非 CRUD）
    // ==================================================================

    /**
     * 解析面试邀约文本。
     * <p>
     * 接收一段面试邀约原文（邮件、飞书卡片、Zoom 链接等），
     * 尝试提取结构化信息。先走规则解析，规则兜不住时降级为 AI 解析。
     *
     * @param request 解析请求（必填：rawText；可选：source）
     * @return 解析结果（含数据结构、置信度、解析方式、日志）
     */
    @PostMapping("/parse")
    public Result<ParseResponse> parse(@Valid @RequestBody ParseRequest request) {
        log.info("解析面试邀约: source={}", request.source());
        ParseResponse response = parseService.parse(request.rawText(), request.source());
        return Result.success(response);
    }

    // ==================================================================
    // Create
    // ==================================================================

    /**
     * 创建面试安排。
     * <p>
     * 公司名称、职位、面试时间为必填项，其余字段可选。
     * 状态默认为 {@link InterviewStatus#PENDING}。
     *
     * @param request 创建请求
     * @return 新建的面试安排
     */
    @PostMapping
    public Result<InterviewScheduleDTO> create(@Valid @RequestBody CreateInterviewRequest request) {
        log.info("创建面试安排: {} - {}", request.companyName(), request.position());
        return Result.success(scheduleService.create(request));
    }

    // ==================================================================
    // Read
    // ==================================================================

    /**
     * 获取面试安排列表。
     * <p>
     * 支持可选的状态过滤和时间范围筛选，用于日历视图和前端的筛选面板。
     * <ul>
     *   <li>{@code ?status=PENDING} — 只看待面试的</li>
     *   <li>{@code ?start=2026-06-01T00:00:00&end=2026-06-30T23:59:59} — 只看六月的</li>
     *   <li>{@code ?status=COMPLETED&start=...&end=...} — 联合过滤</li>
     *   <li>全不传 — 返回全部，按面试时间升序</li>
     * </ul>
     *
     * @param status 状态过滤（可选）
     * @param start  开始时间（可选，ISO 8601）
     * @param end    结束时间（可选，ISO 8601）
     * @return 面试安排列表
     */
    @GetMapping
    public Result<List<InterviewScheduleDTO>> getAll(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return Result.success(scheduleService.getAll(status, start, end));
    }

    /**
     * 获取单个面试安排详情。
     *
     * @param id 面试安排 ID
     * @return 面试安排详情
     */
    @GetMapping("/{id}")
    public Result<InterviewScheduleDTO> getById(@PathVariable Long id) {
        return Result.success(scheduleService.getById(id));
    }

    // ==================================================================
    // Update
    // ==================================================================

    /**
     * 全量更新面试安排。
     * <p>
     * 所有字段都会覆盖。如果只想更新状态，请使用 {@link #updateStatus}。
     *
     * @param id      面试安排 ID
     * @param request 新的数据
     * @return 更新后的面试安排
     */
    @PutMapping("/{id}")
    public Result<InterviewScheduleDTO> update(@PathVariable Long id, @Valid @RequestBody CreateInterviewRequest request) {
        log.info("更新面试安排: id={}", id);
        return Result.success(scheduleService.update(id, request));
    }

    /**
     * 更新面试状态。
     * <p>
     * 与全量更新分离，前端只需传 status 查询参数即可独立修改状态。
     * 支持 PATCH（语义正确）和 PUT（兼容性）两种请求方式。
     *
     * @param id     面试安排 ID
     * @param status 新状态
     * @return 更新后的面试安排
     */
    @PatchMapping("/{id}/status")
    public Result<InterviewScheduleDTO> updateStatus(@PathVariable Long id, @RequestParam InterviewStatus status) {
        log.info("更新面试状态: id={}, status={}", id, status);
        return Result.success(scheduleService.updateStatus(id, status));
    }

    // ==================================================================
    // Delete
    // ==================================================================

    /**
     * 删除面试安排。
     *
     * @param id 面试安排 ID
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除面试安排: id={}", id);
        scheduleService.delete(id);
        return Result.success(null);
    }
}
