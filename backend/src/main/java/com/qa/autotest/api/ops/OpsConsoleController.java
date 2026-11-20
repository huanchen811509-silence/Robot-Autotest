package com.qa.autotest.api.ops;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.ops.OpsTaskService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.ops.OpsTask;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维控制台通道（v5.16.0 / FR-v5.16-01~10）。
 *
 * <p>挂 {@code /api/ops}，不碰既有 {@code /api/admin/ops}；**不执行真实升级**、
 * 不跑非 {@code _it} 库恢复。控制台复用前端既有入口，不新增一级菜单（D-7 取「不新增」）。
 *
 * <p>六类健康指标全部真实取数：取不到就给 {@code null} 并带 {@code hint}，
 * 由前端渲染空态自解释——绝不编一个 0 冒充「正常」。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsConsoleController {

    /** 库名只允许字母数字下划线：调用方传进来的库名一律先过这道闸，再拼 SQL。 */
    private static final Pattern DB_NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");

    /** 校验和判据的三种状态：有清单且一致 / 有清单但不一致 / 没有清单。 */
    public static final String SHA_VERIFIED = "VERIFIED";
    public static final String SHA_MISMATCH = "MISMATCH";
    public static final String SHA_MISSING = "MISSING";

    public record TaskRequest(String taskType, String paramsJson, Long operatorId,
                              Boolean dryRun) {
    }

    public record DrillRequest(String targetDb, Boolean dryRun) {
    }

    public record CompareRequest(String sourceDb, String targetDb) {
    }

    public record BackupView(String name, long sizeBytes, String modifiedAt,
                             String sha256State, String verifyState) {
    }

    public record ServiceHealth(String backend, String frontend, String database,
                                String jdbcUrl, Long uptimeSeconds) {
    }

    public record DatabaseHealth(boolean up, Long sizeMb, Long connections, Long tables,
                                 String hint) {
    }

    public record DiskHealth(long totalBytes, long usableBytes, int usablePercent,
                             String level, String hint) {
    }

    public record RecentCollect(String result, String at, String hint) {
    }

    public record HealthOverview(ServiceHealth service, DatabaseHealth database,
                                 DiskHealth disk, RecentCollect recentCollect,
                                 BackupView recentBackup, String lastDoctor,
                                 String version, int backupFiles, long recentTasks) {
    }

    public record RetainedRelease(String name, String modifiedAt) {
    }

    public record FactsView(String version, String manifestVersion, boolean versionMatch,
                            String lastUpgradeAt, String lastUpgradeResult, String installRoot,
                            String dataDir, DiskHealth disk, List<RetainedRelease> releases,
                            String doctorCommand, String doctorConclusion) {
    }

    public record TableRows(String table, Long sourceRows, Long targetRows, long delta) {
    }

    public record CompareView(String sourceDb, String targetDb, boolean consistent,
                              int comparedTables, List<TableRows> mismatched, String at) {
    }

    public record PrecheckIssue(String kind, String level, String detail) {
    }

    public record PrecheckView(boolean packagePresent, boolean passed,
                               List<PrecheckIssue> issues,
                               String command, String note) {
    }

    public record DrillView(String targetDb, boolean allowed, boolean dryRun,
                            int wouldWriteRows, String command) {
    }

    public record UpgradeView(String command, boolean destructive, String note) {
    }

    public record AuditRow(long taskId, String taskType, String status, String operator,
                           String createdAt, Long durationMs, String result) {
    }

    public record GuardrailsView(String dryRunDefault, String drillDbGuard,
                                 String abortedSweep, String auditCoverage,
                                 String selfCheckCommand) {
    }

    /** 比对只取这些关键表；不在库里的自动跳过，不报假差异。 */
    private static final List<String> KEY_TABLES = List.of(
            "project", "test_case", "test_set", "run", "test_result", "test_report",
            "device", "credential", "app_user", "report_template");

    private final OpsTaskService tasks;
    private final AccessControlService access;
    private final JdbcTemplate jdbc;

    public OpsConsoleController(OpsTaskService tasks, AccessControlService access,
                                JdbcTemplate jdbc) {
        this.tasks = tasks;
        this.access = access;
        this.jdbc = jdbc;
    }

    /**
     * 审计（可选注入）：备份删除是破坏性动作，必须留痕；但既有单测直接 new 本控制器，
     * 所以用 setter 注入而不是加构造器参数。
     */
    private com.qa.autotest.application.iam.AuditService audit;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAudit(com.qa.autotest.application.iam.AuditService audit) {
        this.audit = audit;
    }

    /**
     * 备份文件名白名单（v5.33.0 / FR-v5.33-06）。
     *
     * <p>只允许「字母数字 . _ -」且必须以 .gz 结尾：删文件是按名字拼路径的，
     * 不挡住 `../` 就等于给了一个删任意文件的接口。
     */
    private static final Pattern BACKUP_FILE = Pattern.compile("[A-Za-z0-9._-]{1,120}\\.gz");

    /** 保留策略：只保留最近 N 份，超出的由运维清理（不自动删，先看清单）。 */
    @org.springframework.beans.factory.annotation.Value("${ops.backup.retain-count:20}")
    private int backupRetainCount = 20;

    /**
     * 删除一份备份（及其 .sha256 清单）。
     *
     * @return 实际删掉的文件名列表（顺序：先 .gz 后 .sha256）
     */
    @DeleteMapping("/backups/{name}")
    public ApiResponse<List<String>> deleteBackup(@PathVariable String name,
                                                  @RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        // 与其它运维端点同口径（项目可访问），再叠一道管理员闸：删备份不可逆，不该人人可做
        if (!access.isAdmin()) {
            throw new com.qa.autotest.common.BusinessException(
                    com.qa.autotest.common.ErrorCode.FORBIDDEN, "删除备份仅管理员可操作");
        }
        if (name == null || !BACKUP_FILE.matcher(name).matches() || name.contains("..")) {
            throw new com.qa.autotest.common.BusinessException(
                    com.qa.autotest.common.ErrorCode.INVALID_ARGUMENT,
                    "备份文件名非法，只允许字母数字与 . _ -，且必须以 .gz 结尾");
        }
        Path dir = appHome().resolve("backups");
        Path target = dir.resolve(name).normalize();
        // 二次确认仍在目录内：normalize 之后跑出父目录的一律拒绝
        if (!target.getParent().equals(dir.normalize())) {
            throw new com.qa.autotest.common.BusinessException(
                    com.qa.autotest.common.ErrorCode.INVALID_ARGUMENT, "备份文件名非法");
        }
        if (!Files.isRegularFile(target)) {
            throw new com.qa.autotest.common.BusinessException(
                    com.qa.autotest.common.ErrorCode.NOT_FOUND, "备份不存在: " + name);
        }
        List<String> deleted = new ArrayList<>();
        try {
            Files.delete(target);
            deleted.add(name);
            Path manifest = Paths.get(target + ".sha256");
            if (Files.isRegularFile(manifest)) {
                Files.delete(manifest);
                deleted.add(manifest.getFileName().toString());
            }
        } catch (IOException ex) {
            throw new com.qa.autotest.common.BusinessException(
                    com.qa.autotest.common.ErrorCode.CONFLICT,
                    "删除失败（已删除 " + deleted.size() + " 个文件）：" + ex.getMessage());
        }
        if (audit != null) {
            audit.record("OPS_BACKUP_DELETE", "backup", name,
                    "deleted=" + String.join(",", deleted));
        }
        return ApiResponse.ok(deleted);
    }

    /** 保留策略读数：超期/超量的候选清单由前端勾选删除，后端不自动删。 */
    public record RetentionView(int retainCount, int total, List<String> overRetention) {
    }

    @GetMapping("/backups/retention")
    public ApiResponse<RetentionView> retention(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        List<BackupView> rows = scanBackups();
        // scanBackups 已按文件名倒序（新的在前），超出 retainCount 的就是候选
        List<String> over = rows.stream().skip(Math.max(0, backupRetainCount))
                .map(BackupView::name).toList();
        return ApiResponse.ok(new RetentionView(backupRetainCount, rows.size(), over));
    }

    @GetMapping("/health")
    public ApiResponse<HealthOverview> health(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        List<BackupView> backups = scanBackups();
        return ApiResponse.ok(new HealthOverview(serviceHealth(), databaseHealth(),
                diskHealth(), recentCollect(),
                backups.isEmpty() ? null : backups.get(0), lastDoctor(),
                readVersion(), backups.size(), tasks.count(projectId)));
    }

    @GetMapping("/facts")
    public ApiResponse<FactsView> facts(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        String version = readVersion();
        String manifest = readManifestVersion();
        return ApiResponse.ok(new FactsView(version, manifest,
                manifest != null && manifest.trim().equals(version.trim()),
                readUpgradeAt(), readUpgradeResult(), appHome().toString(),
                readDataDir(), diskHealth(), retainedReleases(),
                "bash scripts/ops/doctor.sh --ci", lastDoctor()));
    }

    private ServiceHealth serviceHealth() {
        boolean dbUp = databaseUp();
        Path web = appHome().resolve("frontend/dist/index.html");
        return new ServiceHealth("UP", Files.isRegularFile(web) ? "BUILT" : "MISSING",
                dbUp ? "UP" : "DOWN", jdbcUrlHint(), uptimeSeconds());
    }

    private DatabaseHealth databaseHealth() {
        if (!databaseUp()) {
            return new DatabaseHealth(false, null, null, null,
                    "数据库不可达：本卡独立失败，其余指标仍可用");
        }
        Long sizeMb = null;
        Long tables = null;
        Long conns = null;
        try {
            sizeMb = jdbc.queryForObject(
                    "SELECT CAST(ROUND(SUM(data_length + index_length) / 1024 / 1024) AS SIGNED)"
                            + " FROM information_schema.tables WHERE table_schema = DATABASE()",
                    Long.class);
            tables = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
                    Long.class);
            conns = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.processlist", Long.class);
        } catch (RuntimeException ex) {
            return new DatabaseHealth(true, sizeMb, conns, tables,
                    "部分读数不可用：" + ex.getClass().getSimpleName());
        }
        return new DatabaseHealth(true, sizeMb, conns, tables, null);
    }

    private DiskHealth diskHealth() {
        try {
            FileStore store = Files.getFileStore(appHome());
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            int percent = total <= 0 ? 0 : (int) Math.round(usable * 100.0 / total);
            String level = percent < 10 ? "CRITICAL" : percent < 20 ? "WARN" : "OK";
            return new DiskHealth(total, usable, percent, level, null);
        } catch (IOException ex) {
            return new DiskHealth(0L, 0L, 0, "UNKNOWN",
                    "磁盘读数不可用（" + ex.getClass().getSimpleName() + "），不是 0 余量");
        }
    }

    private RecentCollect recentCollect() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT result, verified_at FROM credential_verification"
                            + " ORDER BY verified_at DESC LIMIT 1");
            if (rows.isEmpty()) {
                return new RecentCollect(null, null, "尚无采集校验记录");
            }
            Map<String, Object> row = rows.get(0);
            return new RecentCollect(rows.get(0).get("result") == null ? null
                    : String.valueOf(row.get("result")),
                    row.get("verified_at") == null ? null : String.valueOf(row.get("verified_at")),
                    null);
        } catch (RuntimeException ex) {
            return new RecentCollect(null, null, "采集校验表不可读，跳过该指标");
        }
    }

    @GetMapping("/backups")
    public ApiResponse<List<BackupView>> backups(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(scanBackups());
    }

    /**
     * 备份清单只读扫描：目录是 {@code <root>/backups}（与 scripts/ops/common.sh 的
     * {@code BACKUP_DIR} 同源），不是 {@code var/backups}。目录不存在就返回空清单，
     * 由前端空态自解释——不编造条目。
     */
    private List<BackupView> scanBackups() {
        List<BackupView> rows = new ArrayList<>();
        Path dir = appHome().resolve("backups");
        if (!Files.isDirectory(dir)) {
            return rows;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".gz"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .limit(50)
                    .forEach(p -> rows.add(toBackupView(p)));
        } catch (IOException ignored) {
            // 目录不可读时返回空清单，由前端空态自解释
        }
        return rows;
    }

    private BackupView toBackupView(Path p) {
        long size = 0;
        String modified = "";
        try {
            size = Files.size(p);
            modified = Files.getLastModifiedTime(p).toInstant().toString();
        } catch (IOException ignored) {
            // 读不到大小就如实给 0，不编一个值
        }
        Path manifest = Paths.get(p + ".sha256");
        if (!Files.isRegularFile(manifest)) {
            return new BackupView(p.getFileName().toString(), size, modified,
                    SHA_MISSING, "NO_MANIFEST");
        }
        try {
            String expected = Files.readString(manifest).trim().split("\\s+")[0];
            String actual = sha256(p);
            boolean ok = expected.equalsIgnoreCase(actual);
            return new BackupView(p.getFileName().toString(), size, modified,
                    ok ? SHA_VERIFIED : SHA_MISMATCH, ok ? "OK" : "CORRUPTED");
        } catch (IOException ex) {
            return new BackupView(p.getFileName().toString(), size, modified,
                    SHA_MISSING, "UNREADABLE");
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 不可用", ex);
        }
    }

    @GetMapping("/tasks")
    public ApiResponse<List<OpsTask>> tasks(@RequestParam long projectId,
                                            @RequestParam(defaultValue = "20") int limit) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(tasks.list(projectId, limit));
    }

    @GetMapping("/tasks/{taskId:\\d+}")
    public ApiResponse<OpsTask> task(@RequestParam long projectId, @PathVariable long taskId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(tasks.detail(projectId, taskId));
    }

    @PostMapping("/tasks")
    public ApiResponse<OpsTaskService.CreateResult> create(
            @RequestParam long projectId, @RequestBody TaskRequest request) {
        access.requireProjectAccess(projectId);
        TaskRequest safe = request == null
                ? new TaskRequest("UNKNOWN", null, null, true) : request;
        boolean dryRun = !Boolean.FALSE.equals(safe.dryRun());
        return ApiResponse.ok(tasks.create(projectId, safe.taskType(), safe.paramsJson(),
                safe.operatorId(), dryRun));
    }

    @PostMapping("/tasks/{taskId:\\d+}/abort")
    public ApiResponse<Map<String, Object>> abort(@RequestParam long projectId,
                                                  @PathVariable long taskId) {
        access.requireProjectAccess(projectId);
        tasks.abort(projectId, taskId);
        return ApiResponse.ok(Map.of("taskId", taskId, "status", OpsTask.ABORTED));
    }

    @PostMapping("/tasks/sweep")
    public ApiResponse<Map<String, Object>> sweep(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(Map.of("swept", tasks.sweep(projectId),
                "status", OpsTask.ABORTED));
    }

    @PostMapping("/restore-drill")
    public ApiResponse<DrillView> restoreDrill(@RequestParam long projectId,
                                               @RequestBody DrillRequest request) {
        access.requireProjectAccess(projectId);
        DrillRequest safe = request == null ? new DrillRequest("", true) : request;
        String target = OpsTaskService.requireDrillDatabase(safe.targetDb());
        boolean dryRun = !Boolean.FALSE.equals(safe.dryRun());
        // P4 全量审计：演练是运维动作，先落一行再回答；被守卫拒绝的不落行（不留脏审计）。
        tasks.create(projectId, "RESTORE_DRILL",
                "{\"targetDb\":\"" + target + "\"}", null, dryRun);
        return ApiResponse.ok(new DrillView(target, true, dryRun, dryRun ? 0 : 0,
                "bash scripts/ops/db-migrate.sh (DRILL_TARGET_DB=" + target + ")"));
    }

    /**
     * 恢复演练比对（W66-11）：源库 vs 演练库关键表行数。
     *
     * <p>只读：两张库名都过白名单，演练库必须以 {@code _it} 结尾（与恢复演练同一道闸），
     * 否则 400 点名——不允许拿生产库当"演练库"来比。
     */
    @PostMapping("/backup/compare")
    public ApiResponse<CompareView> compare(@RequestParam long projectId,
                                            @RequestBody CompareRequest request) {
        access.requireProjectAccess(projectId);
        CompareRequest safe = request == null ? new CompareRequest("", "") : request;
        String source = requirePlainDb(safe.sourceDb(), "源库");
        String target = OpsTaskService.requireDrillDatabase(safe.targetDb());
        // P4 全量审计：比对也是界面可点的运维动作，落一行（dryRun 语义：只读不写库）。
        tasks.create(projectId, "BACKUP_COMPARE",
                "{\"sourceDb\":\"" + source + "\",\"targetDb\":\"" + target + "\"}",
                null, true);
        List<TableRows> rows = new ArrayList<>();
        int compared = 0;
        for (String table : KEY_TABLES) {
            Long a = countRows(source, table);
            Long b = countRows(target, table);
            if (a == null && b == null) {
                continue;
            }
            compared++;
            long delta = (b == null ? 0 : b) - (a == null ? 0 : a);
            if (delta != 0) {
                rows.add(new TableRows(table, a, b, delta));
            }
        }
        return ApiResponse.ok(new CompareView(source, target, rows.isEmpty(), compared, rows,
                java.time.OffsetDateTime.now().toString()));
    }

    private Long countRows(String db, String table) {
        try {
            return jdbc.queryForObject("SELECT COUNT(*) FROM `" + db + "`.`" + table + "`",
                    Long.class);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @GetMapping("/upgrade/precheck")
    public ApiResponse<PrecheckView> upgradePrecheck(@RequestParam long projectId,
                                                     @RequestParam(required = false)
                                                     String packagePath) {
        access.requireProjectAccess(projectId);
        List<PrecheckIssue> issues = new ArrayList<>();
        DiskHealth disk = diskHealth();
        if ("CRITICAL".equals(disk.level())) {
            issues.add(new PrecheckIssue("DISK", "BLOCKER",
                    "可用空间仅 " + disk.usablePercent() + "%，升级需要额外腾挪空间"));
        } else if ("WARN".equals(disk.level())) {
            issues.add(new PrecheckIssue("DISK", "WARN",
                    "可用空间 " + disk.usablePercent() + "%，建议先清理旧备份"));
        }
        boolean packagePresent = packagePath != null && !packagePath.isBlank()
                && Files.isRegularFile(Paths.get(packagePath));
        if (packagePath != null && !packagePath.isBlank() && !packagePresent) {
            issues.add(new PrecheckIssue("PACKAGE", "BLOCKER",
                    "交付包不存在：" + packagePath));
        }
        String manifest = readManifestVersion();
        String version = readVersion();
        if (manifest != null && !manifest.trim().equals(version.trim())) {
            issues.add(new PrecheckIssue("PACKAGE", "BLOCKER",
                    "MANIFEST.json 版本 " + manifest + " 与 VERSION " + version + " 不一致"));
        }
        Long pending = pendingMigrations();
        if (pending == null) {
            issues.add(new PrecheckIssue("MIGRATION", "WARN", "迁移状态不可读，需人工确认"));
        } else if (pending > 0) {
            issues.add(new PrecheckIssue("MIGRATION", "WARN",
                    "有 " + pending + " 条迁移未应用；升级前确认它们都是可追加的（expand）"));
        }
        boolean passed = issues.stream().noneMatch(i -> "BLOCKER".equals(i.level()));
        return ApiResponse.ok(new PrecheckView(packagePresent, passed, issues,
                "bash scripts/ops/update.sh --dry-run --package <package.tar.gz>",
                "预检只读：不改版本、不写文件、不动数据库；通过后仍需在 CLI 执行升级。"));
    }

    private Long pendingMigrations() {
        try {
            Long applied = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Long.class);
            long files;
            try (Stream<Path> stream = Files.list(appHome().resolve("db/migration"))) {
                files = stream.filter(p -> p.getFileName().toString().endsWith(".sql")).count();
            }
            return applied == null ? null : Math.max(0L, files - applied);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    @GetMapping("/audit")
    public ApiResponse<List<AuditRow>> audit(@RequestParam long projectId,
                                             @RequestParam(defaultValue = "20") int limit) {
        access.requireProjectAccess(projectId);
        List<AuditRow> rows = new ArrayList<>();
        for (OpsTask t : tasks.list(projectId, limit)) {
            rows.add(new AuditRow(t.id(), t.taskType(), t.status(),
                    t.operatorId() == null ? "unknown" : String.valueOf(t.operatorId()),
                    t.createdAt() == null ? null : t.createdAt().toString(),
                    durationMs(t), t.resultJson()));
        }
        return ApiResponse.ok(rows);
    }

    private static Long durationMs(OpsTask t) {
        if (t.startedAt() == null) {
            return null;
        }
        java.time.Instant end = t.finishedAt() == null
                ? java.time.Instant.now() : t.finishedAt();
        return java.time.Duration.between(t.startedAt(), end).toMillis();
    }

    @GetMapping("/guardrails")
    public ApiResponse<GuardrailsView> guardrails(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(new GuardrailsView(
                "backup.sh / purge-demo.sh / update.sh 默认 dry-run；/api/ops/tasks dryRun 缺省 true",
                "restore-drill 与 backup/compare 的演练库强制以 _it 结尾，否则 400 点名",
                "ops_task 含 ABORTED；/api/ops/tasks/sweep 对超租 RUNNING 行判 ABORTED",
                "ops_task 带审计四列；/api/ops/audit 输出操作人 / 时间 / 结果 / 耗时",
                "python3 tools/check_ops_guardrails.py --self-check"));
    }

    private List<RetainedRelease> retainedReleases() {
        List<RetainedRelease> rows = new ArrayList<>();
        Path dir = appHome().resolve("releases");
        if (!Files.isDirectory(dir)) {
            return rows;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted(Comparator.comparing(Path::toString).reversed()).limit(10)
                    .forEach(p -> {
                        String modified = "";
                        try {
                            modified = Files.getLastModifiedTime(p).toInstant().toString();
                        } catch (IOException ignored) {
                            // 时间读不到就留空
                        }
                        rows.add(new RetainedRelease(p.getFileName().toString(), modified));
                    });
        } catch (IOException ignored) {
            // 目录不可读返回空清单
        }
        return rows;
    }

    private boolean databaseUp() {
        try {
            return jdbc.queryForObject("SELECT 1", Integer.class) != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private Long uptimeSeconds() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
    }

    private String jdbcUrlHint() {
        javax.sql.DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            return null;
        }
        try (var conn = ds.getConnection()) {
            String url = conn.getMetaData().getURL();
            int at = url == null ? -1 : url.indexOf('?');
            return at < 0 ? url : url.substring(0, at);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 安装根：`app.home` 优先；否则从 `user.dir` 上跳。
     *
     * <p>部署脚本是从 `backend/` 起进程的，`user.dir` 因此以 `/backend` 结尾，而
     * `VERSION`、`backups/`、`releases/`、`logs/` 都在仓库根——直接把 `user.dir` 当安装根，
     * 会让「当前版本」读成 unknown、「备份清单」永远为空，且看起来像真的没备份。
     */
    private Path appHome() {
        String home = System.getProperty("app.home");
        if (home != null && !home.isBlank()) {
            return Paths.get(home);
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path parent = cwd.getParent();
        if (parent != null && cwd.getFileName() != null
                && "backend".equals(cwd.getFileName().toString())
                && Files.isRegularFile(parent.resolve("VERSION"))) {
            return parent;
        }
        return cwd;
    }

    private String readVersion() {
        return readFirstLine(appHome().resolve("VERSION"));
    }

    private String readManifestVersion() {
        Path manifest = appHome().resolve("dist/MANIFEST.json");
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            String text = Files.readString(manifest);
            int at = text.indexOf("\"version\"");
            if (at < 0) {
                return null;
            }
            int colon = text.indexOf(':', at);
            int quote = text.indexOf('"', colon + 1);
            int end = text.indexOf('"', quote + 1);
            return quote < 0 || end < 0 ? null : text.substring(quote + 1, end);
        } catch (IOException ex) {
            return null;
        }
    }

    private String readDataDir() {
        String name = null;
        try {
            name = jdbc.queryForObject("SELECT DATABASE()", String.class);
        } catch (RuntimeException ignored) {
            // 库名读不到就如实返回 null，不编造路径
        }
        return name == null ? null : "db:" + name;
    }

    private String readUpgradeAt() {
        return readOpsLine("upgrade-history.log", 0);
    }

    private String readUpgradeResult() {
        return readOpsLine("upgrade-history.log", 1);
    }

    private String lastDoctor() {
        return readFirstLine(appHome().resolve("logs/doctor-latest.txt"));
    }

    private String readOpsLine(String file, int index) {
        Path path = appHome().resolve("logs/" + file);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty()) {
                return null;
            }
            String last = lines.get(lines.size() - 1);
            String[] parts = last.split("\\s*\\|\\s*");
            return index < parts.length ? parts[index] : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private String readFirstLine(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path).trim();
        } catch (IOException ex) {
            return null;
        }
    }

    private static String requirePlainDb(String raw, String label) {
        if (raw == null || !DB_NAME.matcher(raw).matches()) {
            throw new IllegalArgumentException(label + "名不合法：" + raw);
        }
        return raw;
    }
}
