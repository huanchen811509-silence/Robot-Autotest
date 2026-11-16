package com.qa.autotest.application.env;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.application.host.HostInventoryCollector;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 交换机 / BMC 探针包（v5.18.0 / FR-v5.18-05 / W68-10）。
 *
 * <p>这一组钉四件事，每件都对应一条验收判据：
 * ① 三类包的命令都来自代码常量（判据 1：⊆ 白名单，不是 DB 拼出来的）；
 * ② deviceType 为空 → 400，不再默认 Linux（判据 2）；
 * ③ 未知类型 → 400 点名，不回落（判据 3）；
 * ④ required 按类独立：交换机版本必采、LLDP 非必采；BMC 只有 mc info 必采（判据 4）。
 *
 * <p>真机采集不在这里验（无样本，判据 5 记 BLOCKED）——单测只负责「建包与白名单一致」。
 */
class ProbeCommandCatalogTest {

    @Test
    void threePacksAreNonEmptyAndDistinct() {
        Map<String, String> linux = ProbeCommandCatalog.forDeviceType("HOST_LINUX");
        Map<String, String> switchPack = ProbeCommandCatalog.forDeviceType("NETWORK_SWITCH");
        Map<String, String> bmc = ProbeCommandCatalog.forDeviceType("BMC");
        assertAll(
                () -> assertNotNull(linux),
                () -> assertNotNull(switchPack),
                () -> assertNotNull(bmc),
                () -> assertEquals(10, linux.size(), "Linux 仍是 10 类（门禁 29 的硬约束）"),
                () -> assertTrue(switchPack.size() >= 4),
                () -> assertTrue(bmc.size() >= 4)
        );
    }

    @Test
    void linuxCatalogIsTheSameReferenceAsCollector() {
        // 引用而不是复制：两份命令必然漂移，那就等于证据失效
        assertEquals(HostInventoryCollector.COMMANDS, ProbeCommandCatalog.forDeviceType("HOST_LINUX"));
    }

    @Test
    void unknownAndBlankTypesReturnNull() {
        assertNull(ProbeCommandCatalog.forDeviceType(null));
        assertNull(ProbeCommandCatalog.forDeviceType(""));
        assertNull(ProbeCommandCatalog.forDeviceType("   "));
        assertNull(ProbeCommandCatalog.forDeviceType("SWITCH_X"));
    }

    @Test
    void typeMatchingIsCaseInsensitive() {
        assertEquals(ProbeCommandCatalog.forDeviceType("BMC"),
                ProbeCommandCatalog.forDeviceType("bmc"));
    }

    @Test
    void allCommandsAreReadOnlyAndLocalePinned() {
        List<String> packs = List.of("HOST_LINUX", "NETWORK_SWITCH", "BMC");
        for (String type : packs) {
            for (Map.Entry<String, String> entry : ProbeCommandCatalog.forDeviceType(type).entrySet()) {
                String command = entry.getValue();
                assertTrue(command.startsWith("export LC_ALL=C;"),
                        type + "/" + entry.getKey() + " 未固定 locale：" + command);
                for (String write : List.of(" set ", "reset", "clear", "delete", "reboot",
                        "configure", "write ", "shutdown")) {
                    assertTrue(!command.contains(write),
                            type + "/" + entry.getKey() + " 含写操作：" + command);
                }
            }
        }
    }

    @Test
    void supportedTypesListedForErrorMessage() {
        assertEquals(List.of("HOST_LINUX", "NETWORK_SWITCH", "BMC"),
                ProbeCommandCatalog.supportedTypes());
    }

    @Test
    void blankDeviceTypeIsRejectedBeforeTouchingDatabase() {
        // jdbc 传 null：判定必须在查库之前完成，否则「没指定类型」会变成一个 500
        ProbePackService service = new ProbePackService(null, null);
        BusinessException blank = assertThrows(BusinessException.class,
                () -> service.commandsFor("  "));
        assertEquals(ErrorCode.INVALID_ARGUMENT, blank.errorCode());
        assertTrue(blank.getMessage().contains("deviceType"));
    }

    @Test
    void unknownDeviceTypeIs400WithSupportedList() {
        ProbePackService service = new ProbePackService(null, null);
        BusinessException unknown = assertThrows(BusinessException.class,
                () -> service.commandsFor("SWITCH_X"));
        assertEquals(ErrorCode.INVALID_ARGUMENT, unknown.errorCode());
        assertTrue(unknown.getMessage().contains("SWITCH_X"));
        assertTrue(unknown.getMessage().contains("NETWORK_SWITCH"));
    }
}
