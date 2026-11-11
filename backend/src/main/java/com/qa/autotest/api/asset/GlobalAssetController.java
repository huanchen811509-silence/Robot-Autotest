package com.qa.autotest.api.asset;

import com.qa.autotest.application.asset.GlobalAssetService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.common.PageResult;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 公共资产库接口（v4.7.0 / FR-v4.7-01..09、v4.9.3 校验和与采集）。
 *
 * <p>资产本体沿用 device / device_port / credential；这里只暴露公共（owner_scope=PLATFORM）视角，
 * 项目私有资产仍走 /api/projects/{id}/assets。
 */
@RestController
@RequestMapping("/api/global/assets")
public class GlobalAssetController {

    /** FR-v4.6-20 同口径防护：单文件 20MB 上限。 */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

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
        return ApiResponse.ok(service.preview(bytes(file), file.getOriginalFilename()));
    }

    @PostMapping("/import/commit")
    public ApiResponse<GlobalAssetService.AssetImportResult> commit(
            @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(service.importAssets(bytes(file), file.getOriginalFilename()));
    }

    @GetMapping("/import/batches")
    public ApiResponse<PageResult<Map<String, Object>>> batches(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.batches(page, size));
    }

    @PostMapping("/{id}/collect")
    public ApiResponse<GlobalAssetService.CollectResult> collect(
            @PathVariable long id,
            @RequestParam(required = false) Long credentialId,
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "false") boolean confirm) {
        return ApiResponse.ok(service.collect(id, credentialId, username, confirm));
    }

    // ------------------------------------------------ 列举与手工录入（v4.12.0）
    //
    // 路由坑：本类已有 GET /{type}/{id}/references、POST /{type}/{id}/reference|unreference
    // 三个「两段路径」映射。上面这条 GET /credentials/{id}/devices 若不把 {id} 写成 \d+，
    // 会把 /credentials/abc/devices 也匹配进来，进而在 service 层抛 NumberFormatException；
    // 反过来，写成 \d+ 才能让 Spring 明确区分「段数相同、语义不同」的两组路由。

    @GetMapping("/credentials/{id:\\d+}/devices")
    public ApiResponse<List<GlobalAssetService.CredentialDevice>> credentialDevices(
            @PathVariable long id) {
        return ApiResponse.ok(service.devicesOfCredential(id));
    }

    @PostMapping("/credentials/{id:\\d+}/collect")
    public ApiResponse<GlobalAssetService.BatchCollectResult> collectByCredential(
            @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean confirm) {
        return ApiResponse.ok(service.collectByCredential(id, confirm));
    }

    /**
     * 手工录入平台凭据。
     *
     * <p>路由落在 {@code /credentials}（一段），与 {@code /{type}/{id}/reference} 不冲突；
     * 当初若把凭据录入设计成 {@code /credentials/create}，就会与 {@code /{type}/{id}} 抢匹配。
     */
    @PostMapping("/credentials")
    public ApiResponse<GlobalAssetService.CredentialView> createCredential(
            @RequestBody GlobalAssetService.CreateCredentialRequest request) {
        return ApiResponse.ok(service.createCredential(request));
    }

    @PostMapping("/devices")
    public ApiResponse<GlobalAssetService.DeviceView> createDevice(
            @RequestBody GlobalAssetService.CreateDeviceRequest request) {
        return ApiResponse.ok(service.createDevice(request));
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

    private static byte[] bytes(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "请先选择要导入的工作簿");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "文件超过 20MB 上限，请拆分后再导入");
        }
        return file.getBytes();
    }
}
