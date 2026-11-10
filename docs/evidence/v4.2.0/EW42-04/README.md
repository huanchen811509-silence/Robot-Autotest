# EW42-04 artifact 端到端下载复核（FR-v4.2-04）

> 结论：**`NOT-RUN`**。本机无 `gh`（`command -v gh` 为空），也没有 GitHub 凭据，
> 无法执行 `gh run download` 与随后对下载件的 `sha256sum -c`。
> 按判据纪律，「链路静态正确」不等于「端到端下载已通过」，`G-05` 保持**未关闭**。

## 1. 判了什么（静态部分）

| 项 | 结论 | 依据 |
|---|---|---|
| workflow 会产出 artifact | 是 | `.github/workflows/delivery.yml` 第 123 行 `actions/upload-artifact@v4` |
| artifact 名字稳定 | `robot-autotest-linux-x64` | 同上第 126 行 |
| 上传内容含校验件 | tarball + `.sha256` + manifest | 同上第 127-129 行 |
| 保留期 | 30 天 | 同上第 132 行 |
| ci 内自校验 | 打包后 `sha256sum -c` 已跑 | 同上第 102 行 |

## 2. 哪些判不了（NOT-RUN）

| 项 | 为什么 | 由什么补 |
|---|---|---|
| `gh run download <id>` 真下载 | 无 gh、无授权 | 有 gh 的机器（或 GitHub Web 下载）取同一 run 的 artifact |
| 下载件 `sha256sum -c` | 无下载件 | 下载后执行；读数写回本目录 |
| 跨环境字节一致 | 同上 | 下载件与 CI 内 `sha256` 行逐字比对 |

## 3. 补齐方法（有 gh 时）

```bash
gh run list --workflow delivery-bundle.yml -L 5
gh run download <run-id> -n robot-autotest-linux-x64 -D /tmp/artifact-check
cd /tmp/artifact-check && sha256sum -c ./*.tar.gz.sha256
```

把 `sha256sum -c` 的完整输出与 `gh run view <id>` 的结论写入本目录，才可把 `G-05` 改判为关闭。
