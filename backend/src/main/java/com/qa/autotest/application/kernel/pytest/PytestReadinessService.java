package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.CredentialRepository;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.HostInventoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 台架就绪度（v5.6.0 / FR-v5.6-04）。
 *
 * <p>为什么需要它：pytest 工程要跑起来，前置条件是「有设备、有凭据、采过环境、绑过脚本」。
 * v5.5.0 那次真实执行是绕过界面直接调 API 把 gpu01/gpu02 登进去的，所以这层依赖一直没人看见；
 * 菜单全量对齐后用户第一次进工程，最该看到的不是覆盖率，而是「台架齐了没有、缺哪一项、点哪儿去配」。
 *
 * <p>四项的口径（都是「有记录即算就绪」，不判断记录内容是否最新）：
 * <ul>
 *   <li>{@code devices} —— 当前工程登记了多少台设备；</li>
 *   <li>{@code credentials} —— 有多少条 SSH 凭据；凭据可以写在进程环境变量里，
 *       但那种做法在界面上不可见、换台机器就失效，所以本判据只认平台内记录；</li>
 *   <li>{@code environment} —— 有没有采集过环境快照（主机巡检记录）；</li>
 *   <li>{@code bindings} —— 绑定了多少个 pytest 脚本。零绑定意味着一条都发不出去。</li>
 * </ul>
 *
 * <p>标签与跳转路径**不放在后端**：那是界面文案与路由，放后端会让 i18n 与路由改名
 * 变成一次后端发版。这里只返回 {@code key} 与 {@code count}，由前端映射。
 */
@Service
public class PytestReadinessService {

    /** 单项就绪度。{@code count} 是该项当前的记录数，0 即未就绪。 */
    public record Item(String key, boolean ready, long count) {
    }

    /** 总判定：四项全就绪才算就绪；缺项由 {@code missing} 显式列出，页面不用自己算。 */
    public record Readiness(boolean ready, List<Item> items, List<String> missing) {
    }

    private final DeviceRepository devices;
    private final CredentialRepository credentials;
    private final HostInventoryRepository inventories;
    private final CaseScriptBindingRepository bindings;

    public PytestReadinessService(DeviceRepository devices, CredentialRepository credentials,
                                  HostInventoryRepository inventories,
                                  CaseScriptBindingRepository bindings) {
        this.devices = devices;
        this.credentials = credentials;
        this.inventories = inventories;
        this.bindings = bindings;
    }

    public Readiness readiness(long projectId) {
        List<Item> items = new ArrayList<>(4);
        items.add(item("devices", devices.listAll(projectId).size()));
        items.add(item("credentials", credentials.list(projectId).size()));
        // limit=1：只关心「有没有」，不要把整张采集历史拉出来。
        items.add(item("environment", inventories.list(projectId, null, 1).size()));
        items.add(item("bindings", bindings.countByProject(projectId, "pytest")));

        List<String> missing = new ArrayList<>();
        for (Item item : items) {
            if (!item.ready()) {
                missing.add(item.key());
            }
        }
        return new Readiness(missing.isEmpty(), items, missing);
    }

    private static Item item(String key, long count) {
        return new Item(key, count > 0, count);
    }
}
