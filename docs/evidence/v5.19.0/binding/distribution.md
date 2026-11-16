# v5.19.0 绑定态分布抽样（FR-v5.19-08）

工程 EHT(#95)，逐页拉全量用例后统计，非抽样。

| 维度 | 取值 | 条数 |
|---|---|---|
| bindingState | UNBOUND | 408 |
| bindingState | BOUND_UNVERIFIED | 61 |
| safetyLevel | LX | 410 |
| safetyLevel | L0 | 41 |
| safetyLevel | L1 | 18 |
| reviewStatus | draft | 469 |

总数：469 条。

口径（大师 2026-11-16 定）：能否被内核识别以脚本 `[Tags] case=<编号>` 为准；
DB 绑定态只作"平台已登记关联"的参考列，两者不一致时以 Tags 结论为准并标橙。
平台侧 `CaseScriptResolver` 只解析 `.robot`，pytest 脚本不在平台内，
故该列对 pytest 工程是"平台不自证"，界面用悬浮说明写明，不假装已验证。
