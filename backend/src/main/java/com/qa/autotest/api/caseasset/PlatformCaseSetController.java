package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.SampleSetSeeder;
import com.qa.autotest.application.caseasset.TestSetService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.common.PageResult;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台用例集库（PUBX-01 / N9 / W57-06）：跨项目可见的用例集资产。
 *
 * <p>平台集不属于任何项目（{@code test_set.project_id IS NULL AND owner_scope='PLATFORM'}），
 * 所以路径不带 projectId。W57-06 起由只读放开为可写：读端点要求 case:read，
 * 写端点统一要求 case:write（D-57-04 已获批准）。
 */
@RestController
@RequestMapping("/api/platform/case-sets")
public class PlatformCaseSetController {

    public record CreatePlatformSetRequest(String setCode, String name, String description) {
    }

    public record UpdatePlatformSetRequest(String name, String description) {
    }

    public record AddItemsRequest(List<Long> caseIds) {
    }

    public record OrderRequest(List<Long> caseIds) {
    }

    /** W57-06：按分类 id 批量加（入参用 id，筛选走 COALESCE 双轨）。 */
    public record CategoryFilterRequest(Long categoryL1Id, Long categoryL2Id) {
    }

    private final TestSetService sets;
    private final SampleSetSeeder seeder;

    /** 构造器注入：TestSetService 的公开 5 参构造器被既有单测直接 new，播种依赖放在控制器层。 */
    public PlatformCaseSetController(TestSetService sets, SampleSetSeeder seeder) {
        this.sets = sets;
        this.seeder = seeder;
    }

    @GetMapping
    public ApiResponse<PageResult<TestSetService.PlatformSetRow>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "all") String source) {
        // 复核 P1-3：GET 不再播种。原实现让 case:read 用户每刷一次列表就写一次 test_set，
        // 与「写需写权限」直接冲突。现在播种只走 POST /seed-samples（有写权限才通），
        // 页面在无样例集时给空态 + 显式按钮（PRD AC-01-5）。
        return ApiResponse.ok(sets.platformPage(keyword, page, Math.min(size, 200), source));
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<TestCase>> items(@PathVariable long id) {
        return ApiResponse.ok(sets.platformItems(id));
    }

    @PostMapping
    public ApiResponse<TestSet> create(@RequestBody CreatePlatformSetRequest request) {
        return ApiResponse.ok(sets.createPlatform(request.setCode(), request.name(),
                request.description()));
    }

    @PutMapping("/{id}")
    public ApiResponse<TestSet> update(@PathVariable long id,
                                       @RequestBody UpdatePlatformSetRequest request) {
        return ApiResponse.ok(sets.updatePlatform(id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        sets.deletePlatform(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/items")
    public ApiResponse<TestSetService.AddItemsResult> addItems(
            @PathVariable long id, @RequestBody AddItemsRequest request) {
        return ApiResponse.ok(sets.addPlatformItems(id, request.caseIds()));
    }

    @DeleteMapping("/{id}/items/{caseId}")
    public ApiResponse<Void> removeItem(@PathVariable long id, @PathVariable long caseId) {
        sets.removePlatformItem(id, caseId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{id}/order")
    public ApiResponse<Void> reorder(@PathVariable long id, @RequestBody OrderRequest request) {
        sets.reorderPlatform(id, request.caseIds());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/items/category-filter")
    public ApiResponse<TestSetService.CategoryFilterResult> addByCategoryFilter(
            @PathVariable long id, @RequestBody CategoryFilterRequest request) {
        return ApiResponse.ok(sets.addPlatformItemsByCategory(id, request.categoryL1Id(),
                request.categoryL2Id()));
    }

    /**
     * 手动触发样例集播种 / 重建。
     *
     * <p>复核 P1-2：原实现无任何鉴权，任何登录用户都能写 {@code test_set / test_set_item}。
     * 播种是「创建集合 + 写成员」，本质是写操作，必须与其它写端点同一道闸。
     */
    @PostMapping("/seed-samples")
    public ApiResponse<SampleSetSeeder.SeedResult> seedSamples(
            @RequestParam(defaultValue = "false") boolean rebuild) {
        sets.requirePlatformWrite();
        return ApiResponse.ok(rebuild ? seeder.rebuild() : seeder.ensureSamples());
    }
}
