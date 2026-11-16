# v5.19.0 计数口径对照（FR-v5.19-07）

同一批 run 在三处的读数，全部来自接口原始响应。

| 出处 | 接口 | 字段 | 值 |
|---|---|---|---|
| 运行记录 | `/api/execution/runs` | totalTests | 70 |
| 运行记录 | `/api/execution/runs` | passedTests | 0 |
| 运行记录 | `/api/execution/runs` | failedTests | 4 |
| 运行记录 | `/api/execution/runs` | skippedTests | None |
| 运行记录 | `/api/execution/runs` | runId | EHT-FAKE-1794805219801 |
| 执行报告 | `/api/reports` | runId | EHT-FAKE-1794805219801 |
| 执行报告 | `/api/reports` | counts | {'pass': 5, 'fail': 4, 'skip': 61} |

判据：运行记录改三段式后，`—` 表示后端未采集（null），`0` 表示确认没有；
两者不得混用，也不得用它冒充"报告里的 5/4/61"。
