package com.qa.autotest.application.report;

import com.qa.autotest.application.repository.KernelViewRepository;
import com.qa.autotest.application.repository.KernelViewRepository.KernelLane;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 双内核统一视图（FR-v5.4-06 / D14 / W54-06）。
 *
 * <p>把 RF 与 pytest 的 run <b>并排</b>摆出来，通过率<b>分别算</b>。
 *
 * <p><b>为什么不做「合并通过率」</b>：两个内核的用例库不是同一批用例。合并之后，
 * 「这个项目 90% 通过」既可能是「RF 主流程全过、pytest 只跑了两条」，
 * 也可能是「两边各有一半失败」——而这两件事的处置完全不同。分列是唯一能说明
 * 「哪一边需要动手」的呈现方式；合并只是好看。
 *
 * <p><b>三向一致性</b>（库编号 ↔ 脚本 ↔ collect）由 `tools/check_pytest_binding.py`
 * 在门禁第 40 步守，本类不重复实现：一致性是「数据对不对」，视图是「怎么展示」，
 * 两者混在一起会让不一致时既看不出问题在哪，也不知道该改哪边。
 */
@Service
public class KernelUnifiedViewService {

    private final KernelViewRepository repository;
    private final Clock clock;

    public KernelUnifiedViewService(KernelViewRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 统一视图。
     *
     * @param bothKernels 是否同时存在两个内核的数据（否则页面不必显示并排布局）
     * @param note        给页面的一句话说明（例如只有一个内核时提示「本项目仅用 RF」）
     */
    public record UnifiedView(int windowDays, List<KernelLane> lanes, boolean bothKernels,
                              String note) {
    }

    public UnifiedView view(long projectId, int days) {
        int window = days == 30 ? 30 : 7;
        Instant since = clock.instant().minus(Duration.ofDays(window));
        List<KernelLane> lanes = repository.lanes(projectId, since);
        boolean both = lanes.size() >= 2;
        String note = lanes.isEmpty() ? "近 " + window + " 天没有执行记录"
                : both ? "两个内核的通过率分别计算，不合并"
                : "近 " + window + " 天只有一个内核在执行（" + lanes.get(0).kernel() + "）";
        return new UnifiedView(window, lanes, both, note);
    }
}
