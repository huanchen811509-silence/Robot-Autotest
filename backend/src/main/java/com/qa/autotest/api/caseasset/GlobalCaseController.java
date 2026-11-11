package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.GlobalCaseService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.common.PageResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 公共用例库接口（v4.6.0 / FR-v4.6-01..13、v4.6.1 / FR-v4.6-20..24）。
 *
 * <p>命名空间 {@code /api/global/**} 由 {@code AccessControlService.requireGlobalEnter()} 把门；
 * 写操作（导入/导出/失效）在服务层进一步限定 ADMIN。
 */
@RestController
@RequestMapping("/api/global/cases")
public class GlobalCaseController {

    public record CategoryRequest(int level, Long parentId, String name, Integer sortNo) {
    }

    /** FR-49-26：项目域分类（projectId 为空表示平台域）。 */
    public record CategoryScopeRequest(Long projectId, int level, Long parentId,
                                       String name, Integer sortNo) {
    }

    /** FR-49-28：两级分类绑定 / 父子组合校验。 */
    public record CategoryBindRequest(Long projectId, Long categoryL1Id, Long categoryL2Id) {
    }

    private final GlobalCaseService service;

    public GlobalCaseController(GlobalCaseService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<GlobalCaseService.GlobalCaseRow>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryL1,
            @RequestParam(required = false) String categoryL2,
            @RequestParam(required = false) String lifecycleStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.page(keyword, categoryL1, categoryL2, lifecycleStatus,
                page, size));
    }

    @GetMapping("/categories")
    public ApiResponse<List<GlobalCaseService.CategoryRow>> categories() {
        return ApiResponse.ok(service.categories());
    }

    @PostMapping("/categories")
    public ApiResponse<GlobalCaseService.CategoryRow> createCategory(
            @RequestBody CategoryRequest request) {
        return ApiResponse.ok(service.createCategory(request.level(), request.parentId(),
                request.name(), request.sortNo() == null ? 0 : request.sortNo()));
    }

    @DeleteMapping("/categories/{id}")
    public ApiResponse<Map<String, Boolean>> deleteCategory(@PathVariable long id) {
        service.deleteCategory(id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @PostMapping("/import/preview")
    public ApiResponse<GlobalCaseService.PreviewResult> preview(
            @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "请先选择要导入的工作簿");
        }
        if (file.getSize() > 20L * 1024 * 1024) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "文件超过 20MB 上限，请拆分后再导入");
        }
        return ApiResponse.ok(service.preview(file.getInputStream(), file.getOriginalFilename()));
    }

    @PostMapping("/import/{batchId}/commit")
    public ApiResponse<GlobalCaseService.CommitResult> commit(@PathVariable long batchId) {
        return ApiResponse.ok(service.commit(batchId));
    }

    @GetMapping("/import/batches")
    public ApiResponse<PageResult<Map<String, Object>>> batches(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.batches(page, size));
    }

    @GetMapping("/import/{batchId}/errors")
    public ResponseEntity<byte[]> errorReport(@PathVariable long batchId) {
        byte[] content = service.errorReport(batchId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"import-" + batchId + "-report.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(content);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String categoryL1,
            @RequestParam(required = false) String lifecycleStatus) {
        GlobalCaseService.ExportFile file = service.export(categoryL1, lifecycleStatus);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.content());
    }

    @PostMapping("/{id}/deprecate")
    public ApiResponse<Map<String, Boolean>> deprecate(@PathVariable long id) {
        service.deprecate(id);
        return ApiResponse.ok(Map.of("deprecated", true));
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<Map<String, Boolean>> restore(@PathVariable long id) {
        service.restore(id);
        return ApiResponse.ok(Map.of("restored", true));
    }

    /** FR-49-07：统一详情契约（四列补录 + 来源徽章；脱敏由 DTO 保证）。 */
    @GetMapping("/{id}")
    public ApiResponse<GlobalCaseService.CaseDetailView> detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/categories/scope")
    public ApiResponse<List<GlobalCaseService.CategoryRow>> categoriesInScope(
            @RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(service.categoriesInScope(projectId));
    }

    @PostMapping("/categories/scope")
    public ApiResponse<GlobalCaseService.CategoryRow> createCategoryInScope(
            @RequestBody CategoryScopeRequest request) {
        return ApiResponse.ok(service.createCategoryInScope(request.projectId(), request.level(),
                request.parentId(), request.name(),
                request.sortNo() == null ? 0 : request.sortNo()));
    }

    /** FR-49-28：有引用时 409 且响应 data.referenceCount 带引用数。 */
    @DeleteMapping("/categories/{id}/scope")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteCategoryInScope(
            @PathVariable long id, @RequestParam(required = false) Long projectId) {
        GlobalCaseService.CategoryDeleteResult result = service.deleteCategoryInScope(projectId, id);
        if (!result.deleted()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiResponse<>(ErrorCode.CONFLICT.code(), ErrorCode.CONFLICT.codeName(),
                            "分类下仍有 " + result.referenceCount() + " 条用例引用，不能删除",
                            Map.of("referenceCount", result.referenceCount())));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("deleted", true, "referenceCount", 0)));
    }

    /** FR-49-28：非法父子组合提交返回 4xx（service 抛 BusinessException）。 */
    @PostMapping("/categories/validate")
    public ApiResponse<Map<String, Boolean>> validateCategoryPair(
            @RequestBody CategoryBindRequest request) {
        service.validateCategoryPair(request.projectId(), request.categoryL1Id(),
                request.categoryL2Id());
        return ApiResponse.ok(Map.of("valid", true));
    }

    /** FR-49-28：项目用例绑定两级分类 id。 */
    @PutMapping("/{id}/categories")
    public ApiResponse<Map<String, Boolean>> bindCaseCategory(
            @PathVariable long id, @RequestBody CategoryBindRequest request) {
        service.bindCaseCategory(request.projectId() == null ? 0L : request.projectId(), id,
                request.categoryL1Id(), request.categoryL2Id());
        return ApiResponse.ok(Map.of("bound", true));
    }

    /** FR-49-30：按分类筛选导出 CSV，每行含 external_id。 */
    @GetMapping("/export/category.csv")
    public ResponseEntity<byte[]> exportCategoryCsv(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String categoryL1,
            @RequestParam(required = false) String categoryL2,
            @RequestParam(required = false) Long intersectWithSetId) {
        GlobalCaseService.ExportCsv file = service.exportCategoryCsv(
                projectId, categoryL1, categoryL2, intersectWithSetId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(file.content());
    }
}
