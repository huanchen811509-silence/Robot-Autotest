package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.CaseBackfillService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公共库 → 项目用例内容回填（FR-UIE-07）。
 *
 * <p>两步式：先 {@code /preview} 看「将填充 N 条 / 跳过 M 条（原因分布）」，
 * 确认无误再 {@code /apply}。回填是写操作，要 {@code CASE:UPDATE}；
 * preview 只读，{@code CASE:READ} 即可。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/case-backfill")
public class CaseBackfillController {

    private final CaseBackfillService service;

    public CaseBackfillController(CaseBackfillService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    public ApiResponse<CaseBackfillService.Preview> preview(@PathVariable long projectId) {
        return ApiResponse.ok(service.preview(projectId));
    }

    @PostMapping("/apply")
    public ApiResponse<CaseBackfillService.Applied> apply(@PathVariable long projectId) {
        return ApiResponse.ok(service.apply(projectId));
    }
}
