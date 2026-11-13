package com.qa.autotest.application.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ReportReadRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.report.KeywordRow;
import com.qa.autotest.domain.report.RunCaseRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * 套件树查询（RPT-02）。
 *
 * <p>数据源是 RPT-01 入库后的 test_result / keyword_result：用例树按 {@code case_key}
 * （suitePath.caseName）重建，步骤树按 keyword_result 的 parent_id 挂接。套件状态由子用例
 * 汇总（任一 FAIL → FAIL；否则任一 PASS → PASS；否则 SKIP；都没有 → UNKNOWN）。
 *
 * <p>日志正文不在这里返回：它按需走 {@code /logs} 的偏移读取，避免一次把大文件塞进响应。
 */
@Service
public class ReportTreeService {

    private final ReportReadRepository read;
    private final AccessControlService access;

    public ReportTreeService(ReportReadRepository read, AccessControlService access) {
        this.read = read;
        this.access = access;
    }

    /** 套件树节点。 */
    public record TreeSuite(String name, String status,
                            List<TreeSuite> suites, List<TreeCase> cases) {
    }

    /**
     * 用例节点。
     *
     * <p>v3.9.0 追加命令三列（FR-v3.9-03）：{@code commandText} 为 {@code NONE} 表示
     * 本用例未下发外部命令，前端据此显示「本用例未下发外部命令」而不是空白；
     * 历史行（v3.9.0 之前入库）三列为 null，前端按「未采集」提示，二者语义不同。
     */
    public record TreeCase(String caseKey, String name, String verdict, long durationMs,
                           String failureMsg, String commandText, String commandSource,
                           Integer commandExitCode, List<TreeKeyword> keywords,
                           int retryCount, boolean flaky) {

        /** 兼容 v3.9.0 之前的六参写法（命令三列按「历史未采集」留 null）。 */
        public TreeCase(String caseKey, String name, String verdict, long durationMs,
                        String failureMsg, List<TreeKeyword> keywords) {
            this(caseKey, name, verdict, durationMs, failureMsg, null, null, null, keywords,
                    0, false);
        }

        /** 兼容 v3.9.0 的九参写法（未复测）。 */
        public TreeCase(String caseKey, String name, String verdict, long durationMs,
                        String failureMsg, String commandText, String commandSource,
                        Integer commandExitCode, List<TreeKeyword> keywords) {
            this(caseKey, name, verdict, durationMs, failureMsg, commandText, commandSource,
                    commandExitCode, keywords, 0, false);
        }
    }

    /** 关键字步骤节点。 */
    public record TreeKeyword(String name, String status, List<TreeKeyword> children) {
    }

    public TreeSuite tree(long projectId, String runId) {
        validate(projectId, runId);
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        if (read.findRun(projectId, runId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "运行不存在: " + runId);
        }
        return loadTree(projectId, runId);
    }

    /**
     * 打印/导出专用读取：不做项目权限判定。
     *
     * <p>调用方必须已经完成授权（导出接口在 {@code ExportService} 内判定；
     * 打印页走一次性 token，token 签发时已校验项目权限）。这里仍按 project_id 查询，
     * 不会跨项目读到别的运行。
     */
    public TreeSuite treeForPrint(long projectId, String runId) {
        validate(projectId, runId);
        if (read.findRun(projectId, runId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "运行不存在: " + runId);
        }
        return loadTree(projectId, runId);
    }

    private TreeSuite loadTree(long projectId, String runId) {
        List<RunCaseRow> cases = read.listCases(projectId, runId);
        Map<String, List<KeywordRow>> keywordsByCase = groupKeywords(read.listKeywords(projectId, runId));

        SuiteBuilder root = new SuiteBuilder(runId);
        for (RunCaseRow row : cases) {
            String caseKey = row.caseKey() == null ? "" : row.caseKey();
            // 连接键格式 suitePath.caseName；分隔点取「最后一个 / 之后的第一个 .」，
            // 这样用例名里带点也不会把套件路径切错。
            int slash = caseKey.lastIndexOf('/');
            int dot = caseKey.indexOf('.', slash + 1);
            String suitePath = dot >= 0 ? caseKey.substring(0, dot) : caseKey;
            String caseName = dot >= 0 ? caseKey.substring(dot + 1) : caseKey;
            SuiteBuilder node = root;
            for (String segment : suitePath.split("/")) {
                if (segment.isBlank()) {
                    continue;
                }
                node = node.child(segment);
            }
            node.cases.add(new TreeCase(caseKey, caseName, row.verdict(), row.durationMs(),
                    row.failureMsg(), row.commandText(), row.commandSource(),
                    row.commandExitCode(), keywordTree(keywordsByCase.get(caseKey)),
                    row.retryCount(), row.flaky()));
        }
        return root.build();
    }

    private static Map<String, List<KeywordRow>> groupKeywords(List<KeywordRow> rows) {
        Map<String, List<KeywordRow>> grouped = new LinkedHashMap<>();
        for (KeywordRow row : rows) {
            grouped.computeIfAbsent(row.caseKey(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static List<TreeKeyword> keywordTree(List<KeywordRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Long, KeywordNodeBuilder> nodes = new LinkedHashMap<>();
        for (KeywordRow row : rows) {
            nodes.put(row.id(), new KeywordNodeBuilder(row.name(), row.status()));
        }
        List<KeywordNodeBuilder> roots = new ArrayList<>();
        for (KeywordRow row : rows) {
            KeywordNodeBuilder node = nodes.get(row.id());
            KeywordNodeBuilder parent = row.parentId() == null ? null : nodes.get(row.parentId());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }
        return roots.stream().map(KeywordNodeBuilder::build).toList();
    }

    private static void validate(long projectId, String runId) {
        if (projectId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 非法");
        }
        if (runId == null || runId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "runId 不能为空");
        }
    }

    /** 可变套件构建器：同名子套件复用同一节点，避免重复建树。 */
    private static final class SuiteBuilder {

        private final String name;
        private final Map<String, SuiteBuilder> children = new TreeMap<>();
        private final List<TreeCase> cases = new ArrayList<>();

        private SuiteBuilder(String name) {
            this.name = name;
        }

        private SuiteBuilder child(String childName) {
            return children.computeIfAbsent(childName, SuiteBuilder::new);
        }

        private TreeSuite build() {
            List<TreeSuite> su = children.values().stream().map(SuiteBuilder::build).toList();
            List<TreeCase> ca = List.copyOf(cases);
            return new TreeSuite(name, statusOf(su, ca), su, ca);
        }

        private static String statusOf(List<TreeSuite> suites, List<TreeCase> cases) {
            boolean pass = false;
            boolean skip = false;
            boolean any = false;
            for (TreeCase test : cases) {
                any = true;
                if ("FAIL".equals(test.verdict())) {
                    return "FAIL";
                }
                if ("PASS".equals(test.verdict())) {
                    pass = true;
                } else if ("SKIP".equals(test.verdict())) {
                    skip = true;
                }
            }
            for (TreeSuite suite : suites) {
                any = true;
                if ("FAIL".equals(suite.status())) {
                    return "FAIL";
                }
                if ("PASS".equals(suite.status())) {
                    pass = true;
                } else if ("SKIP".equals(suite.status())) {
                    skip = true;
                }
            }
            if (pass) {
                return "PASS";
            }
            return any && skip ? "SKIP" : "UNKNOWN";
        }
    }

    private static final class KeywordNodeBuilder {

        private final String name;
        private final String status;
        private final List<KeywordNodeBuilder> children = new ArrayList<>();

        private KeywordNodeBuilder(String name, String status) {
            this.name = name == null ? "" : name;
            this.status = status == null ? "UNKNOWN" : status;
        }

        private TreeKeyword build() {
            return new TreeKeyword(name, status,
                    children.stream().map(KeywordNodeBuilder::build).toList());
        }
    }
}
