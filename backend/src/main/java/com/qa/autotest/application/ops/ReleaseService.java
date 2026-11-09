package com.qa.autotest.application.ops;

import com.qa.autotest.application.repository.OpsRepository;
import com.qa.autotest.domain.ops.ReleaseRecord;
import java.util.List;
import org.springframework.stereotype.Service;

/** 发布记录（FR-3.3-11）：部署脚本写入，运维页读取；回滚时间由脚本回填。 */
@Service
public class ReleaseService {

    private final OpsRepository repo;

    public ReleaseService(OpsRepository repo) {
        this.repo = repo;
    }

    public List<ReleaseRecord> list(int limit) {
        return repo.releases(limit);
    }

    public long record(String version, String status, String note) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("版本号不能为空");
        }
        return repo.insertRelease(version.strip(), status == null ? "DEPLOYED" : status,
                note == null ? null : note.strip());
    }
}
