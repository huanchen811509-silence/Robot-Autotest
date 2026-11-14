package com.qa.autotest.application.execution;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W57-20/21：规范化等价（环境变量/分隔符/尾斜杠）与增删改序必然改变摘要。 */
class CommandDigestTest {

    private static CommandPlan plan(String code, List<String> argv) {
        return new CommandPlan(code, argv, "/opt/ws", 3600, null);
    }

    @Test
    @DisplayName("环境变量排序：顺序不同但语义相同的两份 argv 规范化后相同")
    void 环境变量排序等价() {
        List<String> a = List.of("TZ=Asia/Shanghai", "PATH=/usr/bin", "robot", "suite.robot");
        List<String> b = List.of("PATH=/usr/bin", "TZ=Asia/Shanghai", "robot", "suite.robot");
        assertEquals(CommandDigest.normalizeArgv(a), CommandDigest.normalizeArgv(b));
    }

    @Test
    @DisplayName("分隔符固定：Windows 反斜杠与正斜杠等价")
    void 分隔符固定等价() {
        List<String> a = List.of("robot", "C:\\ws\\suite.robot");
        List<String> b = List.of("robot", "C:/ws/suite.robot");
        assertEquals(CommandDigest.normalizeArgv(a), CommandDigest.normalizeArgv(b));
    }

    @Test
    @DisplayName("路径去尾斜杠：/var/out/ 与 /var/out 等价，根 / 保留")
    void 去尾斜杠等价() {
        List<String> a = List.of("--outputdir", "/var/out/");
        List<String> b = List.of("--outputdir", "/var/out");
        assertEquals(CommandDigest.normalizeArgv(a), CommandDigest.normalizeArgv(b));
        assertEquals("/", CommandDigest.normalizeArgv(List.of("/")).trim());
    }

    @Test
    @DisplayName("环境变量值里的路径同样规范化")
    void 环境变量值路径等价() {
        List<String> a = List.of("ROOT=/data///", "robot");
        List<String> b = List.of("ROOT=/data", "robot");
        assertEquals(CommandDigest.normalizeArgv(a), CommandDigest.normalizeArgv(b));
    }

    @Test
    @DisplayName("规范化等价 → 整份清单 digest 相同")
    void 等价清单摘要相同() {
        List<CommandPlan> a = List.of(plan("C1", List.of("TZ=Asia/Shanghai", "PATH=/bin", "robot", "C:\\ws\\a.robot")),
                plan("C2", List.of("robot", "b.robot")));
        List<CommandPlan> b = List.of(plan("C1", List.of("PATH=/bin", "TZ=Asia/Shanghai", "robot", "C:/ws/a.robot")),
                plan("C2", List.of("robot", "b.robot")));
        assertEquals(CommandDigest.digest(a), CommandDigest.digest(b));
    }

    @Test
    @DisplayName("摘要带固定前缀且为 64 位小写 hex")
    void 摘要格式() {
        String digest = CommandDigest.digest(List.of(plan("C1", List.of("robot"))));
        assertEquals(64, digest.length());
        assertTrue(CommandDigest.isSha256Hex(digest));
        assertTrue(CommandDigest.sha256Hex(CommandDigest.PREFIX + "x").length() == 64);
        assertFalse(CommandDigest.isSha256Hex("ABC"));
    }

    @Test
    @DisplayName("增一条命令 → digest 变化")
    void 增改变摘要() {
        List<CommandPlan> base = List.of(plan("C1", List.of("robot", "a")), plan("C2", List.of("robot", "b")));
        List<CommandPlan> added = List.of(base.get(0), base.get(1), plan("C3", List.of("robot", "c")));
        assertNotEquals(CommandDigest.digest(base), CommandDigest.digest(added));
    }

    @Test
    @DisplayName("删一条命令 → digest 变化")
    void 删改变摘要() {
        List<CommandPlan> base = List.of(plan("C1", List.of("robot", "a")), plan("C2", List.of("robot", "b")));
        assertNotEquals(CommandDigest.digest(base), CommandDigest.digest(List.of(base.get(0))));
    }

    @Test
    @DisplayName("改序 → digest 变化（用例清单顺序不被规范化抹掉）")
    void 改序改变摘要() {
        List<CommandPlan> base = List.of(plan("C1", List.of("robot", "a")), plan("C2", List.of("robot", "b")));
        assertNotEquals(CommandDigest.digest(base),
                CommandDigest.digest(List.of(base.get(1), base.get(0))));
    }

    @Test
    @DisplayName("diff 返回 added/removed/reordered 三个 1-based 编号数组")
    void diff三数组() {
        List<CommandPlan> before = List.of(plan("A", List.of("x")), plan("B", List.of("y")), plan("C", List.of("z")));
        List<CommandPlan> after = List.of(plan("A", List.of("x")), plan("C", List.of("z")), plan("D", List.of("w")));
        CommandDigest.Diff diff = CommandDigest.diff(before, after);
        assertEquals(List.of(3), diff.added());
        assertEquals(List.of(2), diff.removed());
        assertEquals(List.of(2), diff.reordered());
    }

    @Test
    @DisplayName("diffIds 对用例 id 清单同样给出增删改序")
    void diffIds() {
        CommandDigest.Diff diff = CommandDigest.diffIds(List.of(1L, 2L, 3L), List.of(1L, 3L, 4L));
        assertEquals(List.of(3), diff.added());
        assertEquals(List.of(2), diff.removed());
        assertEquals(List.of(2), diff.reordered());
    }

    @Test
    @DisplayName("两份相同清单 diff 全空；shortDigest 为前 12 位")
    void diff空与短摘要() {
        List<CommandPlan> plans = List.of(plan("C1", List.of("robot")), plan("C2", List.of("pytest")));
        CommandDigest.Diff diff = CommandDigest.diff(plans, plans);
        assertTrue(diff.added().isEmpty() && diff.removed().isEmpty() && diff.reordered().isEmpty());
        assertEquals(CommandDigest.digest(plans).substring(0, 12), CommandDigest.shortDigest(plans));
        assertEquals(12, CommandDigest.shortDigest(plans).length());
    }
}
