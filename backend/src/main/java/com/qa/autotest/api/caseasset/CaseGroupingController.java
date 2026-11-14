package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.CaseGroupingService;
import com.qa.autotest.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 全局用例分类归组（W57-08）：GET /api/global/cases/grouped。 */
@RestController
@RequestMapping("/api/global/cases")
public class CaseGroupingController {

    private final CaseGroupingService grouping;

    public CaseGroupingController(CaseGroupingService grouping) {
        this.grouping = grouping;
    }

    @GetMapping("/grouped")
    public ApiResponse<List<CaseGroupingService.L1Group>> grouped() {
        return ApiResponse.ok(grouping.grouped());
    }
}
