package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.CapabilityStat;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.List;
import org.springframework.stereotype.Service;

/** 能力索引（CAS-05）：按 capability 聚合用例数，供用例库筛选与覆盖分析。 */
@Service
public class CapabilityService {

    private final TestCaseRepository cases;
    private final AccessControlService access;
    private final CapabilityCache cache;

    public CapabilityService(TestCaseRepository cases, AccessControlService access) {
        this(cases, access, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CapabilityService(TestCaseRepository cases, AccessControlService access,
                             CapabilityCache cache) {
        this.cases = cases;
        this.access = access;
        this.cache = cache;
    }

    public List<CapabilityStat> list(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        if (cache == null) {
            return cases.capabilities(projectId);
        }
        // 先做权限检查再查缓存：缓存命中不能绕过租户/权限校验。
        return cache.get(projectId, () -> cases.capabilities(projectId));
    }

    /** 某能力下的用例分页。 */
    public PageResult<TestCase> casesOf(long projectId, String capability, int page, int size) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        return cases.page(projectId, null, null, null, null, capability, null, page, size);
    }
}
