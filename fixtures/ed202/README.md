# ED202 原表只读快照

- **快照文件**：`ED202测试用例_V0.4.xlsx`（本目录，ext4，权限 `444` 只读）
- **原表**：`E:/workspace/自动化测试/测试自动化demo程序/参考测试用例/ED202测试用例_V0.4.xlsx`
- **SHA-256**：`9b897b77564eb6d9cb6843fa350b1cd085e61d613a1b047e8ce5ceaa36316e39`
- **复制命令**（在 WSL ext4 权威副本上执行，原表只读）：
  ```bash
  cp "/mnt/e/workspace/自动化测试/测试自动化demo程序/参考测试用例/ED202测试用例_V0.4.xlsx" \
     ~/robot-autotest/fixtures/ed202/ED202测试用例_V0.4.xlsx
  sha256sum ~/robot-autotest/fixtures/ed202/ED202测试用例_V0.4.xlsx
  ```

> **原表只读，导入用此快照。** 严禁改动 E: 冻结副本任何字节；本快照为不可变判据
> 版本（EDIM-04），导入、预检、单测一律以 `fixtures/ed202/` 内这份文件为唯一输入，
> SHA 变化即视为判据变更，须重新走评审。
