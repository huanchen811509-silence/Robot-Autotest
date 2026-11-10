package com.qa.autotest.application.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.workspace.WorkspaceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 包写-读闭环与拒绝路径（FR-v4.1-02 ①②③⑧）。
 *
 * <p>这里刻意不 mock 文件系统：写出来的目录就是用户会拷走的东西，读校验也走真实 sha256 /
 * 真实 HMAC。用假仓储单测覆盖业务分支，用本测试覆盖「字节层不骗人」。</p>
 */
class WorkspaceBundleRoundTripTest {

    private static final String KEY =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private final ObjectMapper mapper = new ObjectMapper();

    private WorkspaceProperties props(Path root) {
        WorkspaceProperties properties = new WorkspaceProperties();
        properties.setRoot(root.toString());
        properties.setSigningKey(KEY);
        return properties;
    }

    private WorkspaceSnapshot snapshot() {
        return new WorkspaceSnapshot(
                new WorkspaceSnapshot.ProjectInfo("demo", "演示项目", "说明", "robot"),
                List.of(new WorkspaceSnapshot.MemberInfo("qa", "QA", "admin")),
                List.of(new WorkspaceSnapshot.CaseInfo("TC-1", "登录", "ui", "manual", "P1",
                        "auth", "", List.of(new WorkspaceSnapshot.StepInfo(1, "打开首页", "看到登录框"))),
                        new WorkspaceSnapshot.CaseInfo("TC-2", "退出", "ui", "manual", "P2",
                        "auth", "", List.of())),
                List.of(new WorkspaceSnapshot.CaseSetInfo("TS-1", "冒烟", "",
                        List.of(new WorkspaceSnapshot.SetItem("TC-1", 1))),
                        new WorkspaceSnapshot.CaseSetInfo("TS-2", "回归", "",
                        List.of(new WorkspaceSnapshot.SetItem("TC-2", 1),
                                new WorkspaceSnapshot.SetItem("TC-1", 2)))),
                List.of(new WorkspaceSnapshot.AssetInfo("dev-1", "DEVICE", "10.0.0.1")),
                List.of(new WorkspaceSnapshot.CredentialInfo("cred-1", "SSH", "root", "***ot")),
                List.of(new WorkspaceSnapshot.EnvironmentInfo("env-1", "test", "")),
                List.of(new WorkspaceSnapshot.ReportInfo("RPT-1", "RUN-1", "报告", "draft",
                        "2026-11-09T00:00:00Z")),
                List.of(new WorkspaceSnapshot.RunInfo("RUN-1", "TS-1", "finished",
                        "2026-11-09T00:00:00Z")),
                List.of(
                        new WorkspaceSnapshot.FileInfo("hello.robot",
                                "*** Test Cases ***\n登录\n    Log    1\n"),
                        new WorkspaceSnapshot.FileInfo("sub/deep.py", "print('x')\n"),
                        new WorkspaceSnapshot.FileInfo("bad/evil.py", "import os\nos.system('id')\n")));
    }

    private WorkspaceBundleWriter.Result write(Path root, String level, String scope) {
        WorkspaceProperties properties = props(root);
        WorkspaceBundleSigner signer = new WorkspaceBundleSigner(properties);
        WorkspaceBundleWriter writer = new WorkspaceBundleWriter(properties, signer, mapper);
        return writer.write(root.resolve("demo"), snapshot(), level, scope);
    }

    private WorkspaceBundleReader reader(Path root) {
        WorkspaceProperties properties = props(root);
        return new WorkspaceBundleReader(properties, new WorkspaceBundleSigner(properties), mapper);
    }

    @Test
    @DisplayName("PROJECT 全量：清单覆盖全部文件，读回字段与内容一致")
    void projectRoundTrip(@TempDir Path root) throws Exception {
        WorkspaceBundleWriter.Result result = write(root, "PROJECT", null);
        WorkspaceBundleReader.VerifiedBundle bundle = reader(root).verify(root.resolve("demo"));

        assertThat(result.fileCount()).isEqualTo(13); // 10 个顶层/域文件 + 3 个源文件
        assertThat(result.totalBytes()).isPositive();
        assertThat(bundle.project()).isEqualTo(
                new WorkspaceSnapshot.ProjectInfo("demo", "演示项目", "说明", "robot"));
        assertThat(bundle.manifest().granularity()).isEqualTo("project");
        assertThat(bundle.manifest().rootPath()).isNull();
        assertThat(bundle.members()).hasSize(1);
        assertThat(bundle.cases()).hasSize(2);
        assertThat(bundle.caseSets()).hasSize(2);
        assertThat(bundle.assets()).hasSize(1);
        assertThat(bundle.credentials()).singleElement()
                .satisfies(c -> assertThat(c.mask()).isEqualTo("***ot"));
        assertThat(bundle.environments()).hasSize(1);
        assertThat(bundle.reports()).hasSize(1);
        assertThat(bundle.runs()).hasSize(1);
        assertThat(bundle.files()).hasSize(3);
        assertThat(bundle.files()).anySatisfy(f -> {
            assertThat(f.path()).isEqualTo("sub/deep.py");
            assertThat(f.content()).isEqualTo("print('x')\n");
        });
        assertThat(Files.readString(root.resolve("demo/README.txt"))).contains("交换格式");
        assertThat(bundle.manifestSha256())
                .isEqualTo(WorkspaceBundleWriter.sha256Hex(
                        Files.readAllBytes(root.resolve("demo/manifest.json"))));
    }

    @Test
    @DisplayName("DIR files/：只带源文件，用例/集合/资产等域清空，成员不抄")
    void dirScope(@TempDir Path root) {
        write(root, "DIR", "files/");
        WorkspaceBundleReader.VerifiedBundle bundle = reader(root).verify(root.resolve("demo"));
        assertThat(bundle.manifest().granularity()).isEqualTo("dir");
        assertThat(bundle.manifest().rootPath()).isEqualTo("files");
        assertThat(bundle.cases()).isEmpty();
        assertThat(bundle.caseSets()).isEmpty();
        assertThat(bundle.assets()).isEmpty();
        assertThat(bundle.members()).isEmpty();
        assertThat(bundle.files()).hasSize(3);
    }

    @Test
    @DisplayName("FILE：只带指定脚本，manifest 也只列一个 files/ 条目")
    void fileScope(@TempDir Path root) throws Exception {
        write(root, "FILE", "files/hello.robot");
        WorkspaceBundleReader.VerifiedBundle bundle = reader(root).verify(root.resolve("demo"));
        assertThat(bundle.manifest().granularity()).isEqualTo("file");
        assertThat(bundle.manifest().rootPath()).isEqualTo("files/hello.robot");
        assertThat(bundle.files()).singleElement()
                .satisfies(f -> assertThat(f.path()).isEqualTo("hello.robot"));
        assertThat(Files.exists(root.resolve("demo/files/sub/deep.py"))).isFalse();
        assertThat(bundle.cases()).isEmpty();
    }

    @Test
    @DisplayName("拒绝：改字节、加文件、改签名、升格式版本")
    void rejectsTamperedPackage(@TempDir Path root) throws Exception {
        write(root, "PROJECT", null);
        Path dir = root.resolve("demo");

        Files.writeString(dir.resolve("files/hello.robot"), "tampered", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader(root).verify(dir))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sha256 不一致");

        write(root, "PROJECT", null); // 复原
        Files.writeString(dir.resolve("files/stray.txt"), "x", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader(root).verify(dir))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目录有、清单无");

        write(root, "PROJECT", null);
        ObjectNode manifest = (ObjectNode) mapper.readTree(dir.resolve("manifest.json").toFile());
        ObjectNode sig = (ObjectNode) manifest.get("signature");
        String value = sig.get("value").asText();
        sig.put("value", (value.charAt(0) == '0' ? "1" : "0") + value.substring(1));
        Files.write(dir.resolve("manifest.json"), mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(manifest));
        assertThatThrownBy(() -> reader(root).verify(dir))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验签失败");

        write(root, "PROJECT", null);
        ObjectNode project = (ObjectNode) mapper.readTree(dir.resolve("project.json").toFile());
        project.put("formatVersion", 2);
        Files.write(dir.resolve("project.json"), mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(project));
        assertThatThrownBy(() -> reader(root).verify(dir))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("formatVersion 必须为 1");
    }

    @Test
    @DisplayName("范围与配额负向：非法粒度、缺范围、目录传文件、绝对/越界路径、超配额")
    void rejectsInvalidScopeAndQuota(@TempDir Path root) {
        assertThatThrownBy(() -> write(root, "FOO", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("粒度必须");
        assertThatThrownBy(() -> write(root, "DIR", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必须指定 rootPath");
        assertThatThrownBy(() -> write(root, "DIR", "cases/cases.json"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必须是目录");
        assertThatThrownBy(() -> write(root, "DIR", "/tmp"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("相对路径");
        assertThatThrownBy(() -> write(root, "FILE", "files/../etc"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非法段");

        WorkspaceProperties small = props(root);
        small.setMaxFiles(5);
        WorkspaceBundleWriter writer = new WorkspaceBundleWriter(small,
                new WorkspaceBundleSigner(small), mapper);
        assertThatThrownBy(() -> writer.write(root.resolve("demo"), snapshot(), "PROJECT", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("配额");

        WorkspaceProperties tiny = props(root);
        tiny.setMaxFileBytes(3);
        assertThatThrownBy(() -> new WorkspaceBundleWriter(tiny,
                new WorkspaceBundleSigner(tiny), mapper)
                .write(root.resolve("demo"), snapshot(), "PROJECT", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("单文件超过配额");
    }

    @Test
    @DisplayName("失败原子性：写失败不覆盖上一份可用包")
    void failedWriteKeepsPreviousBundle(@TempDir Path root) throws Exception {
        write(root, "PROJECT", null);
        String before = WorkspaceBundleWriter.sha256Hex(
                Files.readAllBytes(root.resolve("demo/manifest.json")));
        WorkspaceProperties tiny = props(root);
        tiny.setMaxFiles(2);
        assertThatThrownBy(() -> new WorkspaceBundleWriter(tiny,
                new WorkspaceBundleSigner(tiny), mapper)
                .write(root.resolve("demo"), snapshot(), "PROJECT", null))
                .isInstanceOf(BusinessException.class);
        assertThat(WorkspaceBundleWriter.sha256Hex(
                Files.readAllBytes(root.resolve("demo/manifest.json")))).isEqualTo(before);
        assertThat(Files.list(root).filter(p -> p.getFileName().toString().startsWith(".demo."))
                .toList()).as("临时/回收目录必须清掉").isEmpty();
    }
}
