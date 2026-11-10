package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.GlobalCaseService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.common.PageResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
}
