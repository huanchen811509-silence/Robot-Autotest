package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.GlobalCaseService;
import com.qa.autotest.application.caseasset.TestSetService;
import com.qa.autotest.application.group.ScenarioService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.common.PageResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用例集（CAS-02）。 */
@RestController
@RequestMapping("/api/projects/{projectId}/case-sets")
public class TestSetController {

    public record CreateSetRequest(String setCode, String name, String description) {
    }

    public record UpdateSetRequest(String name, String description) {
    }

    public record AddItemRequest(Long caseId) {
    }

    public record OrderRequest(List<Long> caseIds) {
    }

    /** FR-49-28：分类筛选（全选一级 / 全选二级 / 跨表交集）。 */
    public record CategoryFilterRequest(String categoryL1, String categoryL2,
                                        Long intersectWithSetId) {
    }

    /** FR-49-29：场景创建 / 编组（一层，复用 test_set.kind=scenario）。 */
    public record ScenarioGroupRequest(String setCode, String name, List<Long> caseSetIds) {
    }

    private final TestSetService sets;

    /** 可选装配：既有测试用 1 参构造器直接 new，未注入时场景端点报可读错误。 */
    private ScenarioService scenarios;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setScenarios(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    public TestSetController(TestSetService sets) {
        this.sets = sets;
    }

    private ScenarioService requireScenarios() {
        if (scenarios == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "场景服务未装配");
        }
        return scenarios;
    }

    @GetMapping
    public ApiResponse<PageResult<TestSet>> page(@PathVariable long projectId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(sets.page(projectId, keyword, page, Math.min(size, 200)));
    }

    @GetMapping("/{id}")
    public ApiResponse<TestSet> get(@PathVariable long projectId, @PathVariable long id) {
        return ApiResponse.ok(sets.get(projectId, id));
    }

    @PostMapping
    public ApiResponse<TestSet> create(@PathVariable long projectId,
                                       @RequestBody CreateSetRequest request) {
        return ApiResponse.ok(sets.create(projectId, request.setCode(), request.name(),
                request.description()));
    }

    @PutMapping("/{id}")
    public ApiResponse<TestSet> update(@PathVariable long projectId, @PathVariable long id,
                                       @RequestBody UpdateSetRequest request) {
        return ApiResponse.ok(sets.update(projectId, id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long projectId, @PathVariable long id) {
        sets.delete(projectId, id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<TestCase>> items(@PathVariable long projectId,
                                             @PathVariable long id) {
        return ApiResponse.ok(sets.items(projectId, id));
    }

    @PostMapping("/{id}/items")
    public ApiResponse<Void> addItem(@PathVariable long projectId, @PathVariable long id,
                                     @RequestBody AddItemRequest request) {
        sets.addItem(projectId, id, request.caseId());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}/items/{caseId}")
    public ApiResponse<Void> removeItem(@PathVariable long projectId, @PathVariable long id,
                                        @PathVariable long caseId) {
        sets.removeItem(projectId, id, caseId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{id}/order")
    public ApiResponse<Void> reorder(@PathVariable long projectId, @PathVariable long id,
                                     @RequestBody OrderRequest request) {
        sets.reorder(projectId, id, request.caseIds());
        return ApiResponse.ok(null);
    }

    /** FR-49-28：按分类筛选批量加入，新增条数 = 筛选条数，item_source='category_filter'。 */
    @PostMapping("/{id}/items/category-filter")
    public ApiResponse<TestSetService.CategoryFilterResult> addByCategoryFilter(
            @PathVariable long projectId, @PathVariable long id,
            @RequestBody CategoryFilterRequest request) {
        return ApiResponse.ok(sets.addByCategoryFilter(projectId, id,
                new GlobalCaseService.CategoryFilter(request.categoryL1(), request.categoryL2(),
                        request.intersectWithSetId())));
    }

    /** FR-49-29：创建场景（复用 test_set.kind=scenario 三态，仍限一层）。 */
    @PostMapping("/scenario-groups")
    public ApiResponse<Long> createScenarioGroup(
            @PathVariable long projectId, @RequestBody ScenarioGroupRequest request) {
        return ApiResponse.ok(requireScenarios().createScenarioGroup(
                projectId, request.setCode(), request.name(), request.caseSetIds()));
    }

    /** FR-49-29：编组（更新场景引用的用例集 id 逗号串，不新建中间表）。 */
    @PutMapping("/scenario-groups/{scenarioId}/case-sets")
    public ApiResponse<Map<String, Boolean>> regroupScenario(
            @PathVariable long projectId, @PathVariable long scenarioId,
            @RequestBody ScenarioGroupRequest request) {
        requireScenarios().regroupCaseSets(projectId, scenarioId, request.caseSetIds());
        return ApiResponse.ok(Map.of("regrouped", true));
    }

    /** FR-49-29：删除场景（连同 kind=scenario 的 test_set 行）。 */
    @DeleteMapping("/scenario-groups/{scenarioId}")
    public ApiResponse<Map<String, Boolean>> deleteScenarioGroup(
            @PathVariable long projectId, @PathVariable long scenarioId) {
        requireScenarios().deleteScenarioGroup(projectId, scenarioId);
        return ApiResponse.ok(Map.of("deleted", true));
    }
}
