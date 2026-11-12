package com.qa.autotest.application.kernel.pytest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 型号归一化（FR-v5.2-08 / 验收 B21–B23 / W52-03）。
 *
 * <p>这组用例划的是一条**能力边界**：字符层面的差异必须收敛，语义层面的等价
 * 必须<b>不</b>收敛。前者收敛不了，同一张卡会被登记成多条字典；后者收敛了，
 * 平台就在替业务猜「MT2910 是不是 ConnectX-7」——猜错的代价是基线拿错卡的阈值去比。
 */
class NicModelNormalizerTest {

    @Test
    void 大小写与分隔符差异收敛到同一个键() {
        String expected = NicModelNormalizer.model("ConnectX-7");
        assertEquals(expected, NicModelNormalizer.model("connectx7"));
        assertEquals(expected, NicModelNormalizer.model("  ConnectX_7  "));
        assertEquals(expected, NicModelNormalizer.model("ConnectX  7"));
        assertEquals(expected, NicModelNormalizer.model("ConnectX/7"));
        assertEquals("connectx7", expected,
                "连字符也删：ConnectX-7 与 connectx7 是同一张卡的两种排版");
    }

    @Test
    void 连续分隔符与首尾分隔符被压平() {
        assertEquals("ab", NicModelNormalizer.model("a--b"));
        assertEquals("ab", NicModelNormalizer.model("-a-b-"));
        assertEquals("abc", NicModelNormalizer.model("a _ / b..c"));
    }

    @Test
    void 语义别名不收敛_靠字典行登记() {
        // MT2910 是 ConnectX-7 的料号，但这是业务事实，不是字符规则能推出来的
        assertNotEquals(NicModelNormalizer.model("ConnectX-7"),
                NicModelNormalizer.model("MT2910"));
        assertNotEquals(NicModelNormalizer.model("CX7"), NicModelNormalizer.model("CX6"));
    }

    @Test
    void 厂商更名收敛但不改展示文本() {
        assertEquals("nvidia", NicModelNormalizer.vendor("Mellanox"));
        assertEquals("nvidia", NicModelNormalizer.vendor("NVIDIA"));
        assertEquals("broadcom", NicModelNormalizer.vendor("Broadcom Inc"));
        // 展示文本保留原样：界面上该显示什么就显示什么
        assertEquals("Mellanox", NicModelNormalizer.display("  Mellanox  "));
    }

    @Test
    void 空值与只有分隔符的输入返回_null() {
        assertNull(NicModelNormalizer.model(null));
        assertNull(NicModelNormalizer.model("   "));
        assertNull(NicModelNormalizer.model("---"));
        assertNull(NicModelNormalizer.dictKey(null, "ConnectX-7"));
        assertNull(NicModelNormalizer.dictKey("NVIDIA", "  "),
                "缺型号的字典行会匹配上所有同厂商资产，必须挡住");
    }

    @Test
    void 组合键把厂商与型号一起带上() {
        assertEquals("nvidia|connectx7",
                NicModelNormalizer.assetKey("Mellanox", "ConnectX-7"));
        assertNotEquals(NicModelNormalizer.assetKey("nvidia", "connectx7"),
                NicModelNormalizer.assetKey("broadcom", "connectx7"),
                "同型号不同厂商是两张卡，不能只按型号匹配");
    }
}
