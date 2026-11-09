package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.Priority;
import com.qa.autotest.domain.caseasset.ReviewStatus;
import com.qa.autotest.domain.caseasset.SafetyLevel;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 台账 Excel 导入（CAS-03）。
 *
 * <p>两步式：{@link #preview} 只解析与校验、不落库，把错误整表返回；{@link #commit}
 * 在一个事务里写库，任何一行冲突就整批回滚。EDIM-01..05 沿用此结构，不重写事务边界。
 *
 * <h2>未识别列不再静默忽略（2026-11-08，EDIM-01..05）</h2>
 *
 * <p>多 sheet 遍历、逐 sheet 独立 {@code findHeaderRow()}；未识别列（或撞同一字段被
 * 先出现列挤掉的列）统一进入 {@link ImportPreflight} 的「未映射列」清单，在预检报告里
 * 可见，不再像旧版那样既不报错也不入库。命令类 / 步骤类列中，「测试步骤」按换行编号
 * 拆入 {@code test_case_step}（EDIM-03）；其余扩展列落 {@code test_case} 新增列（EDIM-02/D1）。
 * 记录见 {@code fixtures/ed202/README.md} 与 {@code docs/evidence/v3.0.1/v22/}。
 *
 * <h2>表头解析规则</h2>
 *
 * <p>① 表头行 = 前 {@link #HEADER_SCAN_LIMIT} 行里第一处含任一已知别名的行（看板页
 * 「逐项测试情况」表头落在 R15，故由旧版 10 行放宽到 30 行）；② 别名匹配忽略大小写与
 * 首尾空白；③ <b>多列撞同一字段时先出现的列赢</b>，后面的列进入「未映射列」清单而非丢弃；
 * ④ 优先级 {@code T0/T1/T2} 归一为 {@code P0/P1/P2} 存入 {@code priority}，原值保留在
 * {@code sourcePriority}；无优先级列时全部落 {@link Priority#P2}；⑤ 无安全级列时落
 * {@link SafetyLevel#LX}（未判定，ADR-0013）。
 */
@Service
public class CaseImportService {

    /** 表头扫描上限：看板页逐项清单表头在 R15，故放宽到 30。 */
    private static final int HEADER_SCAN_LIMIT = 30;

    /** UECGSE 适配页：与看板零交集的 83 条，单独读数，不得被静默丢弃。 */
    private static final String UEC_SHEET = "UECGSE适配";

    /** 预检报告对账用的 ED202 规范列（缺哪些进「缺列」清单）。 */
    private static final List<String> ED202_EXPECTED_COLUMNS = List.of(
            "caseCode", "title", "category", "categoryL2", "priority", "precondition",
            "steps", "expected", "caseClass", "testCategory", "ed202Coverage", "safetyLevel");

    /** 步骤行首编号：1. / 1、/ 1) / 1． 均可。 */
    private static final Pattern NUMBERED_LINE = Pattern.compile("^(\\d+)\\s*[.、)．]\\s*(.*)$");

    /**
     * 预览行。safetyLevel 缺列即 LX（未判定，ADR-0013）。
     * 末 8 个字段为 EDIM-01..05 扩展：二级分类 / 源优先级原值 / 用例分类 / 测试归类 /
     * ED202 覆盖情况 / 来源 sheet / 测试步骤 / 源表行号。
     */
    public record ImportRow(int rowNum, String caseCode, String title, String category,
                            String priority, String precondition, String expected, String domainTag,
                            String capability, String description, String safetyLevel,
                            String categoryL2, String sourcePriority, String caseClass,
                            String testCategory, String ed202Coverage, String sourceSheet,
                            List<String> steps, int sourceRow) {

        /** 兼容旧 11 参构造（CaseImportTxIT.rows() 等调用点）：扩展字段取安全默认值。 */
        public ImportRow(int rowNum, String caseCode, String title, String category,
                         String priority, String precondition, String expected, String domainTag,
                         String capability, String description, String safetyLevel) {
            this(rowNum, caseCode, title, category, priority, precondition, expected, domainTag,
                    capability, description, safetyLevel, "", "", "", "", "", "",
                    List.of(), rowNum);
        }
    }

    /** 一行校验错误。 */
    public record RowError(int rowNum, String field, String message) {
    }

    /** 预览结果。 */
    public record ImportPreview(int totalRows, int validRows, List<RowError> errors,
                                List<ImportRow> rows) {
    }

    /** 提交结果。 */
    public record ImportResult(int imported, int skipped) {
    }

    /** DB-free 单 sheet 解析结果（供预检报告与纯解析单测使用）。 */
    public record ParsedSheet(String sheetName, int headerRowIndex, int headerRowNum,
                              Map<String, Integer> columnMap, List<String> unmappedHeaders,
                              List<ImportRow> rows) {

        /** 命中字段数（撞字段只记先出现的那一列）。 */
        public int mappedColumnCount() {
            return columnMap.size();
        }

        /** 解析出的数据行数。 */
        public int rowCount() {
            return rows.size();
        }
    }

    /** DB-free 整本工作簿解析结果；sha256 为原文件只读快照指纹（EDIM-04）。 */
    public record ParsedWorkbook(String sha256, List<ParsedSheet> sheets) {
    }

    /** 表头别名（中英文都收，避免各版本 Excel 反复改字段名）。 */
    private static final Map<String, String> HEADER_ALIASES = new LinkedHashMap<>();

    static {
        alias("caseCode", "用例编号", "编号", "case_code", "casecode", "external_id",
                "测试编号", "测试用例编号");
        alias("title", "标题", "用例名称", "名称", "title", "测试用例",
                "测试名称", "测试用例名称");
        alias("category", "分类", "类型", "case_kind", "category", "一级分类", "测试类型");
        alias("priority", "优先级", "priority");
        alias("precondition", "前置条件", "前置", "precondition", "预置条件");
        alias("expected", "预期结果", "预期", "expected");
        alias("domainTag", "技术域", "域", "domain", "domain_tag");
        alias("capability", "能力", "能力项", "capability");
        alias("description", "描述", "说明", "description");
        // 执行安全级（G2-3 / ADR-0013）：缺列一律落 LX，有列时按 L0/L1/L2/LX 校验。
        alias("safetyLevel", "安全级", "安全级别", "安全等级", "safety_level", "safetylevel");
        // EDIM-02：ED202 原表 10 类别名 —— 编号/名称变体、二级分类、用例分类、测试归类、
        // CX7 结果记录 / ED202 覆盖情况、测试资源。保证逐 sheet 编号列与名称列 100% 命中。
        alias("categoryL2", "二级分类");
        alias("caseClass", "用例分类");
        alias("testCategory", "测试归类", "测试分类");
        alias("ed202Coverage", "ED202覆盖情况", "ED202 覆盖情况",
                "CX7测试结果记录", "CX7测试结果", "测试资源");
        // EDIM-03：测试步骤按换行编号拆入 test_case_step。
        alias("steps", "测试步骤");
    }

    private static void alias(String field, String... names) {
        for (String name : names) {
            HEADER_ALIASES.put(name.toLowerCase(Locale.ROOT), field);
        }
    }

    private final TestCaseRepository cases;
    private final AccessControlService access;
    private final AuditService audit;

    /** 导入成功后让能力索引缓存失效（EW32-03）；测试装配入口不注入时为 null。 */
    private CapabilityCache capabilityCache;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCapabilityCache(CapabilityCache capabilityCache) {
        this.capabilityCache = capabilityCache;
    }

    public CaseImportService(TestCaseRepository cases, AccessControlService access,
                             AuditService audit) {
        this.cases = cases;
        this.access = access;
        this.audit = audit;
    }

    /** 表头 → 字段（供别名单测与预检报告核对 10 类别名）。 */
    public static String mappedField(String headerText) {
        if (headerText == null) {
            return null;
        }
        return HEADER_ALIASES.get(headerText.trim().toLowerCase(Locale.ROOT));
    }

    /** EDIM-02：T0/T1/T2 ↔ P0/P1/P2 归一并留原值；其余原样大写返回，非法值交给 Priority.from 报错。 */
    public static String normalizePriority(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "T0" -> "P0";
            case "T1" -> "P1";
            case "T2" -> "P2";
            case "T3" -> "P3";
            default -> value;
        };
    }

    /** EDIM-03：按换行 + 编号拆步骤；无编号的续行并入当前步骤，返回去掉编号前缀的步骤文本。 */
    public static List<String> splitSteps(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> steps = new ArrayList<>();
        StringBuilder current = null;
        for (String line : raw.split("\\R", -1)) {
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            Matcher matcher = NUMBERED_LINE.matcher(text);
            if (matcher.matches()) {
                if (current != null) {
                    steps.add(current.toString());
                }
                current = new StringBuilder(matcher.group(2).trim());
            } else if (current != null) {
                current.append('\n').append(text);
            } else {
                current = new StringBuilder(text);
            }
        }
        if (current != null) {
            steps.add(current.toString());
        }
        return List.copyOf(steps);
    }

    /** DB-free 解析：读全字节算 SHA（只读冻结）、遍历全部 sheet、逐 sheet 独立 findHeaderRow()。 */
    public static ParsedWorkbook parse(InputStream input) {
        try {
            byte[] bytes = input.readAllBytes();
            String sha256 = sha256Hex(bytes);
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                List<ParsedSheet> sheets = new ArrayList<>();
                for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                    sheets.add(parseSheet(workbook.getSheetAt(s)));
                }
                return new ParsedWorkbook(sha256, List.copyOf(sheets));
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "Excel 解析失败：" + ex.getMessage());
        }
    }

    /** EDIM-05：DB-free 预检报告（四个读数 + 未映射列 / 缺列 / 缺判据条目）。 */
    public static ImportPreflight preflight(InputStream input, String sourceFile) {
        ParsedWorkbook workbook = parse(input);
        Map<String, ImportRow> representative = new LinkedHashMap<>();
        Map<String, Boolean> hasCriteria = new LinkedHashMap<>();
        Map<String, Integer> sheetCountPerCode = new LinkedHashMap<>();
        Set<String> uecCodes = new LinkedHashSet<>();
        Set<String> nonUecCodes = new LinkedHashSet<>();
        List<ImportPreflight.SheetReport> sheetReports = new ArrayList<>();
        List<String> unmappedGlobal = new ArrayList<>();
        List<String> missingColumns = new ArrayList<>();
        int totalRows = 0;

        for (ParsedSheet sheet : workbook.sheets()) {
            Set<String> codesInSheet = new HashSet<>();
            for (ImportRow row : sheet.rows()) {
                totalRows++;
                String key = row.caseCode().toUpperCase(Locale.ROOT);
                representative.putIfAbsent(key, row);
                hasCriteria.merge(key, !row.expected().isBlank(), (a, b) -> a || b);
                codesInSheet.add(key);
                if (UEC_SHEET.equals(sheet.sheetName())) {
                    uecCodes.add(key);
                } else {
                    nonUecCodes.add(key);
                }
            }
            for (String code : codesInSheet) {
                sheetCountPerCode.merge(code, 1, Integer::sum);
            }
            List<String> missingRequired = new ArrayList<>();
            if (!sheet.columnMap().containsKey("caseCode")) {
                missingRequired.add("caseCode");
            }
            if (!sheet.columnMap().containsKey("title")) {
                missingRequired.add("title");
            }
            for (String expectedColumn : ED202_EXPECTED_COLUMNS) {
                if (!sheet.columnMap().containsKey(expectedColumn)) {
                    missingColumns.add(sheet.sheetName() + ":" + expectedColumn);
                }
            }
            sheetReports.add(new ImportPreflight.SheetReport(
                    sheet.sheetName(), sheet.headerRowNum(), sheet.mappedColumnCount(),
                    sheet.rowCount(), List.copyOf(sheet.unmappedHeaders()),
                    List.copyOf(missingRequired)));
            for (String header : sheet.unmappedHeaders()) {
                if (!unmappedGlobal.contains(header)) {
                    unmappedGlobal.add(header);
                }
            }
        }

        int duplicateCodeCount = 0;
        for (int count : sheetCountPerCode.values()) {
            if (count > 1) {
                duplicateCodeCount++;
            }
        }
        List<String> notEvaluatedCodes = new ArrayList<>();
        List<String> missingCriteriaEntries = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : hasCriteria.entrySet()) {
            if (!entry.getValue()) {
                notEvaluatedCodes.add(entry.getKey());
                ImportRow row = representative.get(entry.getKey());
                missingCriteriaEntries.add(entry.getKey() + "@" + row.sourceSheet()
                        + ":R" + row.rowNum() + " " + ImportPreflight.NOT_EVALUATED);
            }
        }
        int totalUnique = representative.size();
        int notEvaluated = notEvaluatedCodes.size();
        return new ImportPreflight(sourceFile, workbook.sha256(), workbook.sheets().size(),
                totalUnique, nonUecCodes.size(), uecCodes.size(), duplicateCodeCount,
                totalRows - totalUnique, totalUnique - notEvaluated, notEvaluated,
                List.copyOf(sheetReports), List.copyOf(unmappedGlobal),
                List.copyOf(missingColumns), List.copyOf(missingCriteriaEntries),
                List.copyOf(notEvaluatedCodes));
    }

    private static ParsedSheet parseSheet(Sheet sheet) {
        int headerIndex = findHeaderRow(sheet);
        Row header = sheet.getRow(headerIndex);
        Map<String, Integer> columns = readHeader(header);
        List<String> unmapped = unmappedHeaders(header, columns);
        DataFormatter formatter = new DataFormatter();
        List<ImportRow> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            String code = cell(formatter, row, columns.get("caseCode"));
            String title = cell(formatter, row, columns.get("title"));
            String rawPriority = cell(formatter, row, columns.get("priority"));
            String rawSafety = cell(formatter, row, columns.get("safetyLevel"));
            if (code.isEmpty() && title.isEmpty() && rawPriority.isEmpty() && rawSafety.isEmpty()) {
                continue;
            }
            rows.add(new ImportRow(i + 1, code, title,
                    cell(formatter, row, columns.get("category")), normalizePriority(rawPriority),
                    cell(formatter, row, columns.get("precondition")),
                    cell(formatter, row, columns.get("expected")),
                    cell(formatter, row, columns.get("domainTag")),
                    cell(formatter, row, columns.get("capability")),
                    cell(formatter, row, columns.get("description")),
                    rawSafety,
                    cell(formatter, row, columns.get("categoryL2")),
                    rawPriority,
                    cell(formatter, row, columns.get("caseClass")),
                    cell(formatter, row, columns.get("testCategory")),
                    cell(formatter, row, columns.get("ed202Coverage")),
                    sheet.getSheetName(),
                    splitSteps(cell(formatter, row, columns.get("steps"))),
                    i + 1));
        }
        return new ParsedSheet(sheet.getSheetName(), headerIndex, headerIndex + 1,
                Collections.unmodifiableMap(new LinkedHashMap<>(columns)),
                List.copyOf(unmapped), List.copyOf(rows));
    }

    /** 解析 + 校验，不写库。多 sheet 遍历；逐 sheet 独立表头，编号去重按整本文件计。 */
    public ImportPreview preview(long projectId, InputStream input) {
        access.bindProject(projectId);
        access.require(PermissionResource.IMPORT, PermissionAction.EXECUTE);
        ParsedWorkbook workbook = parse(input);
        if (workbook.sheets().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "Excel 没有工作表");
        }
        List<ImportRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        int total = 0;
        for (ParsedSheet sheet : workbook.sheets()) {
            if (!sheet.columnMap().containsKey("caseCode")
                    || !sheet.columnMap().containsKey("title")) {
                errors.add(new RowError(sheet.headerRowNum(), "columns",
                        "工作表「" + sheet.sheetName() + "」缺少必需列：用例编号 / 标题"));
                continue;
            }
            for (ImportRow raw : sheet.rows()) {
                total++;
                int rowNum = raw.rowNum();
                String code = raw.caseCode();
                if (code.isEmpty()) {
                    errors.add(new RowError(rowNum, "caseCode", "用例编号为空"));
                    continue;
                }
                if (!seenCodes.add(code.toUpperCase(Locale.ROOT))) {
                    errors.add(new RowError(rowNum, "caseCode", "文件内编号重复：" + code));
                    continue;
                }
                if (cases.findByCode(projectId, code).isPresent()) {
                    errors.add(new RowError(rowNum, "caseCode", "编号已存在于项目：" + code));
                    continue;
                }
                if (raw.title().isEmpty()) {
                    errors.add(new RowError(rowNum, "title", "标题为空"));
                    continue;
                }
                String priority;
                try {
                    priority = Priority.from(raw.priority()).name();
                } catch (IllegalArgumentException ex) {
                    errors.add(new RowError(rowNum, "priority", ex.getMessage()));
                    continue;
                }
                String safetyLevel;
                try {
                    safetyLevel = SafetyLevel.from(raw.safetyLevel()).name();
                } catch (IllegalArgumentException ex) {
                    errors.add(new RowError(rowNum, "safetyLevel", ex.getMessage()));
                    continue;
                }
                rows.add(new ImportRow(rowNum, code, raw.title(), raw.category(), priority,
                        raw.precondition(), raw.expected(), raw.domainTag(), raw.capability(),
                        raw.description(), safetyLevel,
                        raw.categoryL2(), raw.sourcePriority(), raw.caseClass(),
                        raw.testCategory(), raw.ed202Coverage(), raw.sourceSheet(),
                        raw.steps(), raw.sourceRow()));
            }
        }
        return new ImportPreview(total, rows.size(), errors, rows);
    }

    /** 事务提交；任一行冲突整批回滚。 */
    @Transactional
    public ImportResult commit(long projectId, List<ImportRow> rows) {
        access.bindProject(projectId);
        access.require(PermissionResource.IMPORT, PermissionAction.EXECUTE);
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "没有可导入的数据");
        }
        int imported = 0;
        try {
            for (ImportRow row : rows) {
                if (cases.codeExists(projectId, row.caseCode(), null)) {
                    throw new BusinessException(ErrorCode.CONFLICT,
                            "第 " + row.rowNum() + " 行编号已存在：" + row.caseCode());
                }
                TestCase entity = new TestCase(null, projectId, row.caseCode().trim(), row.title(),
                        row.category(), Priority.from(row.priority()).name(), row.precondition(),
                        row.expected(), row.domainTag(), ReviewStatus.DRAFT.code(), "excel",
                        row.capability(), row.description(), SafetyLevel.from(row.safetyLevel()).name());
                long caseId = cases.insert(entity, TenantContext.userId());
                Long operatorId = TenantContext.userId();
                if (hasExtendedData(row)) {
                    cases.writeImportMetadata(caseId, new TestCaseRepository.ImportMetadata(
                            row.categoryL2(), row.sourcePriority(), row.caseClass(),
                            row.testCategory(), row.ed202Coverage(), row.sourceSheet()), operatorId);
                }
                cases.insertImportedSteps(caseId, toImportedSteps(row.steps()), row.sourceRow(),
                        operatorId);
                imported++;
            }
        } catch (DataIntegrityViolationException race) {
            // 两个导入同时提交时，两边都能通过 codeExists 预检，真正兜住重复的是
            // uk_test_case_project_code。不翻译就会落到兜底 500「服务内部错误」，
            // 对外看着像服务故障，实际只是「有人同时在导同一批编号」。
            // 抛 RuntimeException 即让 @Transactional 整批回滚，不留半批。
            throw new BusinessException(ErrorCode.CONFLICT,
                    "并发导入冲突：编号已被另一批次占用，请重新预览后再提交");
        }
        audit.record("case.import", "test_case", String.valueOf(projectId), "imported=" + imported);
        if (capabilityCache != null) {
            capabilityCache.invalidate(projectId);
        }
        return new ImportResult(imported, 0);
    }

    private static boolean hasExtendedData(ImportRow row) {
        return !isBlank(row.categoryL2()) || !isBlank(row.sourcePriority())
                || !isBlank(row.caseClass()) || !isBlank(row.testCategory())
                || !isBlank(row.ed202Coverage()) || !isBlank(row.sourceSheet());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<TestCaseRepository.ImportedStep> toImportedSteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<TestCaseRepository.ImportedStep> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            out.add(new TestCaseRepository.ImportedStep(i + 1, steps.get(i), ""));
        }
        return out;
    }

    /** 逐 sheet 独立找表头：前 HEADER_SCAN_LIMIT 行里第一处含任一已知别名的行。 */
    private static int findHeaderRow(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        for (int i = 0; i <= Math.min(sheet.getLastRowNum(), HEADER_SCAN_LIMIT); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String value = formatter.formatCellValue(row.getCell(c));
                if (HEADER_ALIASES.containsKey(value.trim().toLowerCase(Locale.ROOT))) {
                    return i;
                }
            }
        }
        throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                "工作表「" + sheet.getSheetName() + "」未找到表头行（前 "
                        + HEADER_SCAN_LIMIT + " 行内）");
    }

    private static Map<String, Integer> readHeader(Row header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String raw = formatter.formatCellValue(header.getCell(c)).trim();
            String field = HEADER_ALIASES.get(raw.toLowerCase(Locale.ROOT));
            if (field != null && !columns.containsKey(field)) {
                columns.put(field, c);
            }
        }
        return columns;
    }

    /** 未落列的表头（无别名，或撞同一字段被先出现列挤掉）——进预检报告，不再静默忽略。 */
    private static List<String> unmappedHeaders(Row header, Map<String, Integer> columns) {
        List<String> out = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String raw = formatter.formatCellValue(header.getCell(c)).trim();
            if (raw.isEmpty()) {
                continue;
            }
            String field = HEADER_ALIASES.get(raw.toLowerCase(Locale.ROOT));
            if (field == null || !Integer.valueOf(c).equals(columns.get(field))) {
                out.add(raw);
            }
        }
        return out;
    }

    private static String cell(DataFormatter formatter, Row row, Integer column) {
        if (column == null || column < 0 || column >= row.getLastCellNum()) {
            return "";
        }
        return formatter.formatCellValue(row.getCell(column)).trim();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
