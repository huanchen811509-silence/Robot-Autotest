# v5.9.0 W59-13 回放证据：新用例在 v5.8.0 导航实现上判红

命令：临时回退 navigation.ts / AppNav.vue 到 HEAD（v5.8.0 收口 commit），跑 navigation.spec.ts。
结论：W59-13 的 4 条用例全部判红，说明它们确实锁住了本版修的缺陷（不是恒真断言）。

```
   × W59-13 命中唯一性：任一时刻每组最多一个二级项高亮 > 每个叶子用自己的 path+query 访问，只有它自己亮（≥1 且 ≤1） 8ms
   × W59-13 命中唯一性：任一时刻每组最多一个二级项高亮 > 典型复现路径与详情页：亮且只亮预期那个 1ms
   × W59-13 命中唯一性：任一时刻每组最多一个二级项高亮 > 路由表全量静态路径：每棵树同时高亮的叶子数 ≤1 1ms
   × W59-13 命中唯一性：任一时刻每组最多一个二级项高亮 > isLeafActiveInGroup 与 activeLeaves 同源（侧栏渲染不会各自判一套） 1ms
   × W59-13 命中唯一性：任一时刻每组最多一个二级项高亮 > alsoMatch 裸前缀回归：切到「凭据」页签时「设备」不再跟着亮 4ms
     → expected 2 to be 1 // Object.is equality
AssertionError: expected 2 to be 1 // Object.is equality
      Tests  5 failed | 56 passed (61)
```
