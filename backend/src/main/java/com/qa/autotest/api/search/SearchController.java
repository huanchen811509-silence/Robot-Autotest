package com.qa.autotest.api.search;

import com.qa.autotest.application.search.SearchService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 全局实体搜索（FR-3.1-02）。 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    public ApiResponse<SearchService.Response> search(
            @RequestParam long projectId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "current") String scope,
            @RequestParam(required = false) String types,
            @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(search.search(projectId, q, scope, types, limit));
    }
}
