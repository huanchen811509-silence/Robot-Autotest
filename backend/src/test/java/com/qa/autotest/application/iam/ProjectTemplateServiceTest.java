package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.asset.ProjectAssetService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.Project;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * OPT-06 / FR-49-22~25 的编排单测。
 *
 * <p>用 {@link ProxyFactory} + {@link TransactionInterceptor} 把服务包进<b>真事务代理</b>
 * （记录型 {@link PlatformTransactionManager}，连库不需要），从而证明：
 * ① 默认 mode=copy；② 写 project_asset 之后注入失败 → 整个 {@code @Transactional} 回滚、
 * 业务写入零残留、无假成功审计；③ 同一 templateId+code/name 二次提交 409、项目只建一次。
 *
 * <p>SQL 细节用可覆写的 protected 钩子隔离；编排顺序、事务边界、审计字段是这里被测的真东西。
 */
class ProjectTemplateServiceTest {

    private static volatile boolean duplicateExists;
    private static volatile boolean failAfterAssets;
    private static final List<String> WRITES = new ArrayList<>();
    private static volatile boolean syncRegistered;

    private JdbcTemplate jdbc;
    private ProjectService projectService;
    private ProjectAssetRepository assetRepository;
    private AuditService audit;
    private RecordingTxManager txManager;
    private ProjectTemplateService service;

    @BeforeEach
    void setUp() {
        duplicateExists = false;
        failAfterAssets = false;
        WRITES.clear();
        syncRegistered = false;
        TenantContext.clear();

        jdbc = mock(JdbcTemplate.class);
        AccessControlService access = mock(AccessControlService.class);
        audit = mock(AuditService.class);
        ProjectAssetService projectAssets = mock(ProjectAssetService.class);
        assetRepository = mock(ProjectAssetRepository.class);
        projectService = mock(ProjectService.class);
        txManager = new RecordingTxManager();

        when(projectService.create(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    recordWrite("project");
                    return new Project(101L, inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(3), true, 1L, "private", "robot");
                });
        when(projectService.nextCode(anyString(), anyString())).thenReturn("PRJ-RF-202611-001");
        doAnswer(inv -> {
            recordWrite("project_asset");
            return null;
        }).when(assetRepository).addReference(anyLong(), anyString(), anyLong(), any(), any());

        // 真事务代理：只让 @Transactional 的 createProjectFromTemplate 进入事务
        TestableTemplateService target = new TestableTemplateService(jdbc, access, audit,
                projectAssets, assetRepository, projectService);
        TransactionInterceptor interceptor = new TransactionInterceptor(txManager,
                new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        service = (ProjectTemplateService) factory.getProxy();

        TenantContext.set(1L, "tester", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("mode 缺省/空白一律 copy；reference 显式保留（锁死默认值）")
    void defaultModeIsCopy() {
        assertThat(new CreateProjectFromTemplateRequest(7L, "项目", null, null).mode())
                .isEqualTo("copy");
        assertThat(new CreateProjectFromTemplateRequest(7L, "项目", null, "  ").resolvedMode())
                .isEqualTo("copy");
        assertThat(new CreateProjectFromTemplateRequest(7L, "项目", null, "reference").mode())
                .isEqualTo("reference");
    }

    @Test
    @DisplayName("成功编排：单事务提交，审计含 operator/templateId/projectId/mode=copy")
    void orchestrationCommitsAndAudits() {
        service.createProjectFromTemplate(new CreateProjectFromTemplateRequest(7L, "新项目", null, null));

        assertThat(WRITES).containsExactly("project", "content", "project_asset");
        assertThat(txManager.commits).isEqualTo(1);
        assertThat(txManager.rollbacks).isZero();

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("template.create_project"), eq("project"), eq("101"),
                detail.capture());
        assertThat(detail.getValue()).contains("operator=1", "templateId=7", "projectId=101",
                "mode=copy");
    }

    @Test
    @DisplayName("失败零残留：写 project_asset 之后注入 RuntimeException → 整体回滚")
    void failureRollsBackEverything() {
        failAfterAssets = true;

        assertThatThrownBy(() -> service.createProjectFromTemplate(
                new CreateProjectFromTemplateRequest(7L, "失败项目", null, "copy")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("注入失败");

        // 事务回滚后：所有业务写入（project / 用例集 / project_asset）被丢弃，零残留
        assertThat(WRITES).as("回滚后不得残留任何业务写入").isEmpty();
        assertThat(txManager.rollbacks).isEqualTo(1);
        assertThat(txManager.commits).isZero();
        // 假成功审计绝不落库
        verify(audit, never()).record(eq("template.create_project"), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("重复提交 409：同一 templateId + code/name 第二次冲突，项目总数只 +1")
    void duplicateSubmissionIsConflict() {
        CreateProjectFromTemplateRequest request =
                new CreateProjectFromTemplateRequest(7L, "同名项目", "SAME-CODE", "copy");

        duplicateExists = false;
        service.createProjectFromTemplate(request);
        duplicateExists = true;
        assertThatThrownBy(() -> service.createProjectFromTemplate(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.CONFLICT));

        // 第二次在幂等检查处就被挡下，建项目只发生一次
        verify(projectService, times(1)).create(anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    // ------------------------------------------------------------------ 测试替身

    /** 记录型事务管理器：不连库，真实驱动 @Transactional 的 commit/rollback 与同步回调。 */
    static class RecordingTxManager extends AbstractPlatformTransactionManager {
        int begins;
        int commits;
        int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }

    /** 覆写 SQL 相关钩子，用「写入记录」替代真实 SQL；注入点位于 project_asset 之后。 */
    static class TestableTemplateService extends ProjectTemplateService {
        TestableTemplateService(JdbcTemplate jdbc, AccessControlService access, AuditService audit,
                                ProjectAssetService projectAssets,
                                ProjectAssetRepository assetRepository,
                                ProjectService projectService) {
            super(jdbc, access, audit, projectAssets, assetRepository, projectService);
        }

        @Override
        protected TemplateRow one(long id) {
            return new TemplateRow(id, "模板", "TPL-RF-202611-001", "robot", "team", false,
                    "active", 1L, "", 0, "2026-11-11");
        }

        @Override
        protected boolean duplicateProjectExists(String source, String code, String name) {
            return duplicateExists;
        }

        @Override
        protected List<TemplateItemRow> loadTemplateItems(long templateId) {
            return List.of();
        }

        @Override
        protected List<TemplateAssetRow> loadTemplateAssets(long templateId) {
            return List.of(new TemplateAssetRow("DEVICE", 11L));
        }

        @Override
        protected void generateContent(long projectId, List<TemplateItemRow> items, String mode,
                                       long operator) {
            recordWrite("content");
        }

        @Override
        protected void afterTemplateAssetsReferenced(long projectId) {
            if (failAfterAssets) {
                throw new RuntimeException("注入失败：写 project_asset 之后");
            }
        }
    }

    /** 记录一次业务写入，并在首次写入时注册「回滚即清空」的事务同步。 */
    private static void recordWrite(String what) {
        if (!syncRegistered && TransactionSynchronizationManager.isSynchronizationActive()) {
            syncRegistered = true;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        WRITES.clear();
                    }
                    syncRegistered = false;
                }
            });
        }
        WRITES.add(what);
    }
}
