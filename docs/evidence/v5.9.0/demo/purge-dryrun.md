# purge-demo.sh dry-run 真实读数

时间：2026-11-15T12:28:29+08:00
库：127.0.0.1:3306/robot_dev
命令：scripts/ops/purge-demo.sh（不加 --apply，只盘点）

## 输出

[purge-demo] 目标库：127.0.0.1:3306/robot_dev
[purge-demo] is_demo=1 盘点
  app_user         0
  project          2
[purge-demo] 演示项目下的从属数据（本脚本不删，需走项目删除接口）
  test_case        0
  run              0
  test_result      0
  test_report      0
[purge-demo] dry-run：未删除任何行。
[purge-demo] 确认无误后执行：PURGE_DEMO_CONFIRM=YES scripts/ops/purge-demo.sh --apply

## 结论

dry-run 期间未执行任何 DELETE；加 --apply 还需 PURGE_DEMO_CONFIRM=YES。
