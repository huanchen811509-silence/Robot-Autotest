package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.ProjectCardRepository;
import com.qa.autotest.application.repository.ProjectCardRepository.ProjectCardAggregate;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectCard;
import com.qa.autotest.domain.iam.RoleCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 项目卡牌聚合（PRD v2.3 FR-UI-09 / FR-UI-10，工作包 UI-02）。
 *
 * <p><b>可见范围直接复用</b> {@link ProjectService#visible()}（内部 {@code access.visibleProjects()}）：
 * 卡牌集合必须与 {@code GET /api/projects} 逐 id 相等，否则会出现「列表看得到、卡牌看不到」这类
 * 只能靠猜的 bug。这里<b>不新写任何权限判断</b>——权限口径只有一处，负向用例见
 * {@code ProjectCardVisibilityTest}。
 *
 * <p><b>一次 SQL</b>：拿到 id 列表后只调用一次
 * {@link ProjectCardRepository#aggregate}（{@code project_id IN (...)} 分组），
 * 不逐项目查（N+1）。{@code ProjectCardServiceTest} 用 fake 仓储把调用次数锁死为 1。
 *
 * <p><b>聚合口径</b>（写在这里，避免"看代码才知道"）：
 * <ul>
 *   <li>{@code caseCount} = 该项目 {@code test_case} 行数；</li>
 *   <li>{@code runCount} = 窗口内 {@code run} 行数（按 {@code started_at}，为空退 {@code created_at}），
 *       不限状态；</li>
 *   <li>{@code passRateBp} = 窗口内 {@code run_status='finished'} 的
 *       {@code sum(passed_tests)/sum(total_tests)} 折算成万分位整数（0..10000）；
 *       分母为 0 时返回 {@code null}，<b>不拿 0 冒充</b>——0 与「没跑过」是两件事；</li>
 *   <li>{@code lastRunAt} = 该项目最近一条 run 的时间，<b>不限窗口</b>，无则 {@code null}；</li>
 *   <li>{@code deviceCount} / {@code memberCount} = 直接计数；</li>
 *   <li>{@code myRole} = 当前用户在该项目的 {@code member_role}；
 *       全局 admin 且无成员关系时给 {@code admin}；否则 {@code null}。</li>
 * </ul>
 *
 * <p><b>{@code isCurrent}</b>：当前项目是前端 {@code localStorage} 状态，服务端无从得知，
 * 所以由调用方把 {@code current} 回传，这里只做 {@code id == current} 的比较；不传则全部
 * {@code false}。这样前端一次请求就能拿到全部卡牌，不必逐项目再比对。
 */
@Service
public class ProjectCardService {

    /** 支持的窗口档位。 */
    public static final String WINDOW_7D = "7d";
    public static final String WINDOW_30D = "30d";

    private static final String DEFAULT_WINDOW = WINDOW_7D;

    private final ProjectService projects;
    private final ProjectCardRepository cards;
    private final AccessControlService access;
    private final Clock clock;

    public ProjectCardService(ProjectService projects, ProjectCardRepository cards,
                              AccessControlService access, Clock executionClock) {
        this.projects = projects;
        this.cards = cards;
        this.access = access;
        this.clock = executionClock;
    }

    /**
     * 当前用户可见的全部项目卡牌。
     *
     * @param window           统计窗口，{@code 7d} / {@code 30d}；缺省 {@code 7d}，其他值 400
     * @param currentProjectId 前端当前项目 id，可为 {@code null}（此时所有卡牌 {@code isCurrent=false}）
     */
    public List<ProjectCard> cards(String window, Long currentProjectId) {
        int days = windowDays(window);
        List<Project> visible = projects.visible();
        if (visible.isEmpty()) {
            return List.of();
        }
        long userId = TenantContext.requireUserId();
        Instant windowStart = clock.instant().minus(Duration.ofDays(days));
        List<Long> projectIds = visible.stream().map(Project::id).toList();
        Map<Long, ProjectCardAggregate> aggregates = cards.aggregate(projectIds, windowStart, userId);
        boolean admin = access.isAdmin(userId);

        List<ProjectCard> result = new ArrayList<>(visible.size());
        for (Project project : visible) {
            ProjectCardAggregate aggregate = aggregates.getOrDefault(project.id(), ProjectCardAggregate.EMPTY);
            result.add(new ProjectCard(
                    project.id(), project.code(), project.name(), project.description(),
                    project.enabled(), project.visibility(), project.ownerId(),
                    currentProjectId != null && currentProjectId.equals(project.id()),
                    aggregate.caseCount(), aggregate.runCount(), passRateBp(aggregate),
                    aggregate.lastRunAt(), aggregate.deviceCount(), aggregate.memberCount(),
                    myRole(aggregate.memberRole(), admin)));
        }
        return result;
    }

    /** 窗口解析：只认 {@code 7d} / {@code 30d}，空值按默认档，其他一律 400。 */
    static int windowDays(String window) {
        String value = window == null || window.isBlank() ? DEFAULT_WINDOW : window.trim();
        return switch (value) {
            case WINDOW_7D -> 7;
            case WINDOW_30D -> 30;
            default -> throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "非法的统计窗口：" + window + "（仅支持 7d / 30d）");
        };
    }

    /** 万分位通过率；分母为 0 返回 {@code null}，不拿 0 冒充。 */
    private static Integer passRateBp(ProjectCardAggregate aggregate) {
        long total = aggregate.finishedTotalTests();
        if (total <= 0) {
            return null;
        }
        return (int) Math.round(aggregate.finishedPassedTests() * 10000.0 / total);
    }

    /** 成员角色优先；无成员关系的全局管理员兜底为 {@code admin}；其余 {@code null}。 */
    private static String myRole(String memberRole, boolean admin) {
        if (memberRole != null && !memberRole.isBlank()) {
            return memberRole;
        }
        return admin ? RoleCode.ADMIN.code() : null;
    }
}
