package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.common.async.BoundedExecutor;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.AuditEntry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计写入（NFR-v0.2-06 / HRD-02）。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>异步写</b>：不占用业务事务，也不把审计写失败变成业务失败；</li>
 *   <li><b>失败只告警</b>：写入异常 {@code log.warn} 留痕，业务照常返回；</li>
 *   <li><b>只增不改</b>：本类只提供写入，不存在 update / delete 方法。</li>
 * </ol>
 *
 * <p>摘要中的口令 / 令牌 / 密钥值统一替换为 {@code ***}，见 {@link #sanitize}。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /**
     * 敏感键值对：键名命中即把值替换为 ***（中英文键名都覆盖）。
     *
     * <p>分隔符允许前面带一个引号，以覆盖 JSON 形态 {@code "password":"abc"}；
     * 该引号保留在第二组里，替换后仍是合法 JSON。
     */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|credential|口令|密码|密钥)"
                    + "(\"?\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^,;\\s}]+)");

    /** 摘要最大长度，避免超长文本撑爆审计表。 */
    private static final int SUMMARY_MAX = 1000;

    private final AuditRepository audit;
    private final Executor executor;

    /** 生产装配：共用一个有界执行器，队列满降级同步、停机排空（EW32-04）。 */
    @Autowired
    public AuditService(AuditRepository audit, BoundedExecutor executor) {
        this.audit = audit;
        this.executor = executor;
    }

    /** 测试装配：同步执行，保证断言时审计已落库。 */
    public AuditService(AuditRepository audit) {
        this(audit, Runnable::run);
    }

    /** 测试可注入同步执行器（{@code Runnable::run}）以避免等待异步线程。 */
    public AuditService(AuditRepository audit, Executor executor) {
        this.audit = audit;
        this.executor = executor == null ? Runnable::run : executor;
    }

    /** 兼容旧调用：同步落库，失败只告警。 */
    public void record(String action, String resourceType, String resourceId, String detail) {
        write(action, resourceType, resourceId, sanitize(detail), null, null);
    }

    /**
     * 变更类审计：记录前后摘要，异步落库。
     *
     * <p>{@code before}/{@code after} 只放可读元信息（name / code / status / role 等），
     * 不得放口令、令牌、密文。删除类操作传 {@code after = null}，只记前值。
     */
    public void recordChange(String action, String resourceType, String resourceId,
                             String before, String after) {
        String summary = summaryOf(before, after);
        execute(action, resourceType, resourceId, summary);
    }

    /** 执行失败时留一条失败审计（不写「成功」），供排查。 */
    public void recordFailure(String action, String resourceType, String resourceId,
                              String reason) {
        execute(action, resourceType, resourceId, "failed=true; reason=" + sanitize(reason));
    }

    private void execute(String action, String resourceType, String resourceId, String summary) {
        Long projectId = TenantContext.projectId();
        Long actorId = TenantContext.userId();
        String operator = TenantContext.username();
        String ip = currentIp();
        String safeSummary = truncate(summary);

        executor.execute(() -> write(action, resourceType, resourceId, null, safeSummary,
                new Context(projectId, actorId, operator, ip)));
    }

    private void write(String action, String resourceType, String resourceId, String detail,
                       String summary, Context context) {
        try {
            Long projectId = context == null ? TenantContext.projectId() : context.projectId();
            Long actorId = context == null ? TenantContext.userId() : context.actorId();
            String operator = context == null ? TenantContext.username() : context.operator();
            String ip = context == null ? currentIp() : context.ip();
            audit.insertEntry(new AuditEntry(null, projectId, actorId, operator, action,
                    resourceType, resourceId, detail, summary, ip, Instant.now()));
        } catch (RuntimeException ex) {
            // 审计写失败绝不阻断业务，但必须留痕
            log.warn("审计写入失败 action={} resource={} id={}：{}",
                    action, resourceType, resourceId, ex.toString());
        }
    }

    /**
     * 脱敏：password=xxx / token: "yyy" / 口令=zzz 一律替换为 ***。
     *
     * <p>带引号的值保留引号（{@code "abc"} → {@code "***"}），避免把 JSON / 日志里的
     * 结构引号一起删掉后产生非法片段。
     */
    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = SENSITIVE.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String value = matcher.group(3);
            String masked = "***";
            if (value.length() >= 2
                    && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                    && value.charAt(value.length() - 1) == value.charAt(0)) {
                masked = value.charAt(0) + "***" + value.charAt(0);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + masked));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 生成前后摘要；删除只记前值，新增只记后值。 */
    static String summaryOf(String before, String after) {
        if (before == null && after == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (before != null && !before.isBlank()) {
            sb.append("before={").append(sanitize(before)).append('}');
        }
        if (after != null && !after.isBlank()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("after={").append(sanitize(after)).append('}');
        }
        if (after == null) {
            sb.append("; deleted=true");
        }
        if (before == null) {
            sb.append("; created=true");
        }
        return sb.toString();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= SUMMARY_MAX) {
            return value;
        }
        return value.substring(0, SUMMARY_MAX) + "…";
    }

    private static String currentIp() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servlet) {
                HttpServletRequest request = servlet.getRequest();
                String forwarded = request.getHeader("X-Forwarded-For");
                if (forwarded != null && !forwarded.isBlank()) {
                    return forwarded.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (RuntimeException ignored) {
            // 非 Web 线程（定时任务 / 单测）没有请求上下文，IP 留空
        }
        return null;
    }

    private record Context(Long projectId, Long actorId, String operator, String ip) {
    }
}
