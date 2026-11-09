package com.qa.autotest.api.view;

import com.qa.autotest.application.view.SavedView;
import com.qa.autotest.application.view.SavedViewService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 项目内保存视图（FR-3.1-03）；user_id 全部取自上下文，不接受前端传递。 */
@RestController
@RequestMapping("/api/projects/{projectId}/views")
public class SavedViewController {

    private final SavedViewService views;

    public SavedViewController(SavedViewService views) {
        this.views = views;
    }

    @GetMapping
    public ApiResponse<List<SavedView>> list(@PathVariable long projectId,
                                              @RequestParam(defaultValue = "case") String scope) {
        return ApiResponse.ok(views.list(projectId, scope));
    }

    @PostMapping
    public ApiResponse<SavedView> create(@PathVariable long projectId,
                                         @RequestBody SavedViewService.SaveRequest request) {
        return ApiResponse.ok(views.create(projectId, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SavedView> update(@PathVariable long projectId, @PathVariable long id,
                                     @RequestBody SavedViewService.SaveRequest request) {
        return ApiResponse.ok(views.update(projectId, id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable long projectId, @PathVariable long id) {
        return ApiResponse.ok(views.delete(projectId, id));
    }
}
