package com.qa.autotest.application.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ReportTemplateRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.report.ReportTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告模板（TPLX-01）：白名单守门、必填守门、顺序归一、版本自增、平台级只读。
 *
 * <p>不连库、不启 Spring。三条最容易被写成「看起来能过」的用例都在这儿：
 * ① 只判「字段非空」不判白名单 → 模板能存进非法字段，导出时渲染器抛 500；
 * ② 采信客户端传来的 `sortOrder` → 前端拖排出两个同序号时顺序不确定；
 * ③ 允许改平台级模板 → A 项目改一下，B 项目的导出跟着变。
 */
class ReportTemplateServiceTest {

    private static final long PROJECT = 11L;
    private static final long OTHER_PROJECT = 22L;

    private FakeTemplateRepository templates;
    private ReportTemplateService service;

    @BeforeEach
    void setUp() {
        templates = new FakeTemplateRepository();
        service = new ReportTemplateService(templates, new PermissiveAccess(), new SilentAudit());
    }

    @Test
    @DisplayName("白名单外字段：400，且错误文案点名非法键")
    void 白名单外字段被拒() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(command("t1", List.of("reportId", "title", "version",
                        "frozenAt", "disclaimer", "watermark", "rawOutput", "commands"))));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("rawOutput"), ex.getMessage());
        assertTrue(ex.getMessage().contains("commands"), ex.getMessage());
        assertTrue(templates.rows.isEmpty(), "非法请求不得落库");
    }

    @Test
    @DisplayName("重复字段：400，且只报一次重复键")
    void 重复字段被拒() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(command("t1", List.of("reportId", "title", "version",
                        "frozenAt", "disclaimer", "watermark", "passRate", "passRate"))));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("passRate"), ex.getMessage());
        assertEquals(1, ex.getMessage().split("passRate", -1).length - 1, "重复键只报一次");
    }

    @Test
    @DisplayName("缺少必填字段：400，且把缺的字段全列出来")
    void 缺必填字段被拒() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(command("t1", List.of("reportId", "passRate"))));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        for (String required : List.of("title", "version", "frozenAt", "disclaimer", "watermark")) {
            assertTrue(ex.getMessage().contains(required), "未列出缺失字段 " + required);
        }
    }

    @Test
    @DisplayName("字段顺序由服务端按提交顺序重排，客户端序号一律不采信")
    void 顺序归一() {
        ReportTemplateView view = service.create(command("硬件组视图",
                List.of("reportId", "title", "version", "frozenAt", "disclaimer", "watermark",
                        "passRate", "swBuild")));

        assertEquals(1, view.version());
        assertEquals("硬件组视图", view.name());
        assertFalse(view.platform());
        List<String> keys = view.fields().stream().map(ReportTemplateView.FieldView::fieldKey).toList();
        assertEquals(List.of("reportId", "title", "version", "frozenAt", "disclaimer", "watermark",
                "passRate", "swBuild"), keys);
        for (int i = 0; i < view.fields().size(); i++) {
            assertEquals(i, view.fields().get(i).sortOrder(), "第 " + i + " 项顺序不对");
        }
    }

    @Test
    @DisplayName("创建时字段必须真的落库（insert 只落主表，字段要另写）")
    void 创建时字段落库() {
        ReportTemplateView created = service.create(command("t1", fullKeys()));
        ReportTemplate stored = templates.rows.get(created.id());
        assertTrue(stored != null, "模板未落库");
        assertEquals(21, stored.fields().size(), "字段没跟着写进去");
        assertEquals(1, templates.fieldWrites.getOrDefault(created.id(), 0), "replaceFields 未被调用");
    }

    @Test
    @DisplayName("更新一次 version + 1，字段整体替换")
    void 更新后版本自增() {
        ReportTemplateView created = service.create(command("t1", fullKeys()));
        assertEquals(1, created.version());

        ReportTemplateView updated = service.update(created.id(),
                command("t1-renamed", List.of("reportId", "title", "version", "frozenAt",
                        "disclaimer", "watermark", "conclusion")));
        assertEquals(2, updated.version());
        assertEquals("t1-renamed", updated.name());
        assertEquals(7, updated.fields().size());
    }

    @Test
    @DisplayName("平台级模板（project_id=0）只读：改或删都 400")
    void 平台级模板只读() {
        long platformId = templates.seed(ReportTemplate.PLATFORM_PROJECT_ID, "DEFAULT",
                fullKeys(), true);

        BusinessException update = assertThrows(BusinessException.class,
                () -> service.update(platformId, command("DEFAULT2", fullKeys())));
        assertEquals(ErrorCode.INVALID_ARGUMENT, update.errorCode());
        assertTrue(update.getMessage().contains("平台级"), update.getMessage());

        BusinessException delete = assertThrows(BusinessException.class,
                () -> service.delete(PROJECT, platformId));
        assertTrue(delete.getMessage().contains("平台级"), delete.getMessage());
    }

    @Test
    @DisplayName("跨项目模板 403；不存在的模板 404（两者对调用方含义不同）")
    void 跨项目与不存在() {
        long foreign = templates.seed(OTHER_PROJECT, "别人的模板", fullKeys(), false);

        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> service.get(PROJECT, foreign));
        assertEquals(ErrorCode.FORBIDDEN, forbidden.errorCode());

        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.get(PROJECT, 999_999L));
        assertEquals(ErrorCode.NOT_FOUND, missing.errorCode());
    }

    @Test
    @DisplayName("空字段列表：400（空模板导出等于一张白纸）")
    void 空字段被拒() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(command("t1", List.of())));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("至少"), ex.getMessage());
    }

    @Test
    @DisplayName("设为默认：本项目只保留一个默认，平台级回落值被取消")
    void 设为默认唯一() {
        long platformId = templates.seed(ReportTemplate.PLATFORM_PROJECT_ID, "DEFAULT",
                fullKeys(), true);
        ReportTemplateView first = service.create(command("a", fullKeys()));
        ReportTemplateView second = service.create(command("b", fullKeys()));

        service.setDefault(PROJECT, first.id());
        assertEquals(first.id(), templates.findDefault(PROJECT).orElseThrow().id());

        service.setDefault(PROJECT, second.id());
        assertEquals(second.id(), templates.findDefault(PROJECT).orElseThrow().id());
        assertFalse(templates.rows.get(platformId).isDefault(), "平台级默认应被让位");
    }

    @Test
    @DisplayName("可选字段清单来自白名单，顺序稳定且含 21 项")
    void 可选字段清单() {
        List<String> fields = service.selectableFields();
        assertEquals(ReportFieldWhitelist.EXTERNAL_ORDER, fields);
        assertEquals(21, fields.size());
        assertTrue(fields.containsAll(ReportFieldWhitelist.REQUIRED_FIELDS));
    }

    @Test
    @DisplayName("W64-17：复制平台模板 → 本项目出现可改副本，字段 / 标题 / 内核逐项一致")
    void 复制平台模板生成项目副本() {
        long sourceId = templates.seed(ReportTemplate.PLATFORM_PROJECT_ID, "EHT-PYTEST",
                fullKeys(), true);
        List<ReportTemplate.Field> sourceFields = List.of(
                new ReportTemplate.Field("reportId", 0, null),
                new ReportTemplate.Field("title", 1, "报告标题"),
                new ReportTemplate.Field("conclusion", 2, null));
        templates.rows.put(sourceId, new ReportTemplate(sourceId, 0L, "EHT-PYTEST", "",
                1, true, sourceFields, null, Instant.EPOCH, ReportTemplate.KERNEL_PYTEST));

        ReportTemplateView copy = service.copy(PROJECT, sourceId);

        assertFalse(copy.platform(), "副本必须是项目级、可改");
        assertEquals(PROJECT, copy.projectId());
        assertEquals("EHT-PYTEST-副本", copy.name());
        assertEquals(ReportTemplate.KERNEL_PYTEST, copy.kernel(), "内核不能回落成 robot");
        assertEquals(sourceFields.size(), copy.fields().size(), "字段数必须与源一致");
        assertEquals(List.of("reportId", "title", "conclusion"),
                copy.fields().stream().map(ReportTemplateView.FieldView::fieldKey).toList());
        assertEquals(List.of(0, 1, 2),
                copy.fields().stream().map(ReportTemplateView.FieldView::sortOrder).toList());
        assertEquals("报告标题", copy.fields().get(1).titleOverride(), "自定义标题要一起复制");
        assertEquals(1, templates.fieldWrites.getOrDefault(copy.id(), 0),
                "insert 之外必须显式 replaceFields，否则副本是 0 字段");
        // 源一行未动
        assertEquals(sourceFields, templates.rows.get(sourceId).fields());
        assertEquals(0L, templates.rows.get(sourceId).projectId());
    }

    @Test
    @DisplayName("W64-17：副本名同名顺延；改副本不影响平台源")
    void 副本名顺延且改副本不动源() {
        long sourceId = templates.seed(ReportTemplate.PLATFORM_PROJECT_ID, "DEFAULT",
                fullKeys(), true);
        templates.seed(PROJECT, "DEFAULT-副本", fullKeys(), false);

        ReportTemplateView copy = service.copy(PROJECT, sourceId);
        assertEquals("DEFAULT-副本-2", copy.name(), "不能覆盖已有同名模板");

        service.update(copy.id(), command("改过的副本", fullKeys()));
        ReportTemplate source = templates.rows.get(sourceId);
        assertEquals("DEFAULT", source.name());
        assertEquals(0L, source.projectId());
        assertEquals(fullKeys(), source.fields().stream()
                .map(ReportTemplate.Field::fieldKey).toList(), "改副本不得改动平台源");
    }

    @Test
    @DisplayName("W64-17：跨项目源 403；空字段源 400，且都不落库")
    void 复制的源边界() {
        long otherId = templates.seed(OTHER_PROJECT, "OTHER", fullKeys(), false);
        long emptyId = templates.seed(ReportTemplate.PLATFORM_PROJECT_ID, "EMPTY",
                List.of(), true);
        // 基线必须在两个前置样本都 seed 之后取，否则把第二个样本误算成「复制落库」。
        int before = templates.rows.size();

        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> service.copy(PROJECT, otherId));
        assertEquals(ErrorCode.FORBIDDEN, forbidden.errorCode());

        BusinessException invalid = assertThrows(BusinessException.class,
                () -> service.copy(PROJECT, emptyId));
        assertEquals(ErrorCode.INVALID_ARGUMENT, invalid.errorCode());
        assertEquals(before, templates.rows.size(), "被拒的复制不得落库");
    }

    @Test
    @DisplayName("W64-17：权限口径与 create 一致 —— 无 EXECUTION:UPDATE 被拒且不落库")
    void 复制不允许绕过权限() {
        long sourceId = templates.seed(ReportTemplate.PLATFORM_PROJECT_ID, "DEFAULT",
                fullKeys(), true);
        int before = templates.rows.size();
        AccessControlService denying = new AccessControlService(null, null) {
            @Override
            public void bindProject(long projectId) {
                // 测试里不做项目成员校验
            }

            @Override
            public void require(PermissionResource resource, PermissionAction action) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "缺少 EXECUTION:UPDATE");
            }
        };
        ReportTemplateService guarded = new ReportTemplateService(templates, denying,
                new SilentAudit());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guarded.copy(PROJECT, sourceId));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        assertEquals(before, templates.rows.size(), "权限被拒时不得生成副本");
    }

    private static List<String> fullKeys() {
        return ReportFieldWhitelist.EXTERNAL_ORDER;
    }

    private static ReportTemplateService.TemplateCommand command(String name, List<String> keys) {
        List<ReportTemplateService.FieldCommand> fields = new ArrayList<>();
        for (String key : keys) {
            fields.add(new ReportTemplateService.FieldCommand(key, null));
        }
        return new ReportTemplateService.TemplateCommand(PROJECT, name, "", fields);
    }

    /** 内存仓储：只实现服务层用到的语义（可见性按 project_id=0 或本项目）。 */
    static final class FakeTemplateRepository implements ReportTemplateRepository {

        final Map<Long, ReportTemplate> rows = new LinkedHashMap<>();
        /** 记录每个模板写过几次字段，用来断言「insert 之后必须再写字段」。 */
        final Map<Long, Integer> fieldWrites = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        long seed(long projectId, String name, List<String> keys, boolean isDefault) {
            long id = seq.getAndIncrement();
            List<ReportTemplate.Field> fields = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                fields.add(new ReportTemplate.Field(keys.get(i), i, null));
            }
            rows.put(id, new ReportTemplate(id, projectId, name, "", 1, isDefault, fields,
                    null, Instant.EPOCH));
            return id;
        }

        @Override
        public List<ReportTemplate> listVisible(long projectId) {
            return rows.values().stream()
                    .filter(t -> t.projectId() == ReportTemplate.PLATFORM_PROJECT_ID
                            || t.projectId() == projectId)
                    .toList();
        }

        @Override
        public Optional<ReportTemplate> find(long projectId, long id) {
            ReportTemplate template = rows.get(id);
            if (template == null) {
                return Optional.empty();
            }
            boolean visible = template.projectId() == ReportTemplate.PLATFORM_PROJECT_ID
                    || template.projectId() == projectId;
            return visible ? Optional.of(template) : Optional.empty();
        }

        @Override
        public Optional<ReportTemplate> findByName(long projectId, String name) {
            return rows.values().stream()
                    .filter(t -> t.projectId() == projectId && t.name().equals(name))
                    .findFirst();
        }

        @Override
        public boolean existsInOtherProject(long projectId, long id) {
            ReportTemplate template = rows.get(id);
            return template != null
                    && template.projectId() != ReportTemplate.PLATFORM_PROJECT_ID
                    && template.projectId() != projectId;
        }

        @Override
        public long insert(ReportTemplate draft, Long operatorId) {
            long id = seq.getAndIncrement();
            // 与真实实现一致：insert 只落主表一行，字段必须另走 replaceFields。
            // 这里若图省事把 draft.fields() 一起存了，会掩盖「服务层漏写字段」这类 bug
            // （TPLX-01 真实 HTTP 验收就是这么抓到过一次）。
            rows.put(id, new ReportTemplate(id, draft.projectId(), draft.name(),
                    draft.description(), draft.version(), draft.isDefault(), List.of(),
                    operatorId, Instant.EPOCH, draft.kernel()));
            return id;
        }

        @Override
        public void update(long projectId, long id, String name, String description,
                           Long operatorId) {
            ReportTemplate old = rows.get(id);
            rows.put(id, new ReportTemplate(id, old.projectId(), name, description,
                    old.version() + 1, old.isDefault(), old.fields(), old.createdBy(),
                    old.createdAt(), old.kernel()));
        }

        @Override
        public void delete(long projectId, long id) {
            rows.remove(id);
        }

        @Override
        public void replaceFields(long projectId, long templateId,
                                  List<ReportTemplate.Field> fields, Long operatorId) {
            fieldWrites.merge(templateId, 1, Integer::sum);
            ReportTemplate old = rows.get(templateId);
            rows.put(templateId, new ReportTemplate(templateId, old.projectId(), old.name(),
                    old.description(), old.version(), old.isDefault(), fields,
                    old.createdBy(), old.createdAt(), old.kernel()));
        }

        @Override
        public Optional<ReportTemplate> findDefault(long projectId) {
            return rows.values().stream()
                    .filter(ReportTemplate::isDefault)
                    .filter(t -> t.projectId() == ReportTemplate.PLATFORM_PROJECT_ID
                            || t.projectId() == projectId)
                    .max((a, b) -> Long.compare(a.projectId(), b.projectId()));
        }

        @Override
        public void markDefault(long projectId, long id) {
            rows.replaceAll((key, value) -> {
                boolean inScope = value.projectId() == ReportTemplate.PLATFORM_PROJECT_ID
                        || value.projectId() == projectId;
                boolean shouldBeDefault = key == id;
                if (!inScope || value.isDefault() == shouldBeDefault) {
                    return value;
                }
                return new ReportTemplate(value.id(), value.projectId(), value.name(),
                        value.description(), value.version(), shouldBeDefault, value.fields(),
                        value.createdBy(), value.createdAt());
            });
        }
    }

    static final class PermissiveAccess extends AccessControlService {

        PermissiveAccess() {
            super(null, null);
        }

        @Override
        public void bindProject(long projectId) {
            // 测试里不做项目成员校验
        }

        @Override
        public void require(PermissionResource resource, PermissionAction action) {
            // 测试里不做角色校验
        }
    }

    /** 审计桩：只收集，不落库。 */
    static final class SilentAudit extends AuditService {

        final List<String> actions = new ArrayList<>();

        SilentAudit() {
            super(null);
        }

        @Override
        public void record(String action, String resourceType, String resourceId, String detail) {
            actions.add(action);
        }
    }
}
