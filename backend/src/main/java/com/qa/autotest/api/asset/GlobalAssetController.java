package com.qa.autotest.api.asset;

import com.qa.autotest.application.asset.GlobalAssetService;
import com.qa.autotest.common.ApiResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 公共资产库接口（v4.7.0 / FR-v4.7-01..09）。
 *
 * <p>资产本体沿用 device / device_port / credential；这里只暴露公共（owner_scope=PLATFORM）视角，
 * 项目私有资产仍走 /api/projects/{id}/assets。
 */
@RestController
@RequestMapping("/api/global/assets")
public class GlobalAssetController {

    private final GlobalAssetService service;

    public GlobalAssetController(GlobalAssetService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<GlobalAssetService.AssetRow>> list(
            @RequestParam(defaultValue = "DEVICE") String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String availability) {
        return ApiResponse.ok(service.list(type, keyword, availability));
    }

    @GetMapping("/{type}/{id}/references")
    public ApiResponse<Map<String, Integer>> references(@PathVariable String type,
                                                        @PathVariable long id) {
        return ApiResponse.ok(service.references(type, id));
    }

    @PostMapping("/import/preview")
    public ApiResponse<GlobalAssetService.AssetPreview> preview(
            @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(service.preview(file.getInputStream()));
    }

    @PostMapping("/import/commit")
    public ApiResponse<GlobalAssetService.AssetImportResult> commit(
            @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(service.importAssets(file.getInputStream()));
    }

    @PostMapping("/{type}/{id}/reference")
    public ApiResponse<Map<String, Boolean>> reference(@PathVariable String type,
                                                       @PathVariable long id,
                                                       @RequestParam long projectId) {
        service.reference(projectId, type, id);
        return ApiResponse.ok(Map.of("referenced", true));
    }

    @PostMapping("/{type}/{id}/unreference")
    public ApiResponse<Map<String, Boolean>> unreference(@PathVariable String type,
                                                         @PathVariable long id,
                                                         @RequestParam long projectId) {
        service.unreference(projectId, type, id);
        return ApiResponse.ok(Map.of("unreferenced", true));
    }

    @DeleteMapping("/{type}/{id}")
    public ApiResponse<Map<String, Boolean>> delete(@PathVariable String type,
                                                    @PathVariable long id) {
        service.delete(type, id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        GlobalAssetService.ExportFile file = service.exportAssets();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.content());
    }
}
