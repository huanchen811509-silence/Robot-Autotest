package com.qa.autotest.application.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 规范形 JSON / HMAC 签名 / 恶意模式扫描的纯单测（FR-v4.1-02 ⑩、ADR-4X-12）。
 *
 * <p>这三块都有一条「两边必须一致」的硬约束：规范形要与 Python 机检逐字节一致，
 * 签名要区分任何一位变化，扫描要「只提示不拦包」。错在这里不会报错，只会静默放行。</p>
 */
class WorkspaceBundleCryptoTest {

    private static final String KEY =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private final ObjectMapper mapper = new ObjectMapper();

    private WorkspaceProperties props(Path root) {
        WorkspaceProperties properties = new WorkspaceProperties();
        properties.setRoot(root.toString());
        properties.setSigningKey(KEY);
        return properties;
    }

    @Test
    @DisplayName("规范形：键字典序、无空格、UTF-8 原样、整数不带小数点")
    void canonicalJson() throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("z", 1);
        node.put("a", "中文");
        node.putObject("m").put("b", true).putArray("list").add(2).add("x");
        assertThat(CanonicalJson.write(node))
                .isEqualTo("{\"a\":\"中文\",\"m\":{\"b\":true,\"list\":[2,\"x\"]},\"z\":1}");
        // 缩进/空白绝不能进规范形：否则 Java 与 Python 的 HMAC 会各算一份
        assertThat(CanonicalJson.write(node)).doesNotContain(": ", ", ");
    }

    @Test
    @DisplayName("签名：同一清单稳定、改字段/改签名/换密钥均失败")
    void signatureIsStableAndStrict(@TempDir Path root) throws Exception {
        WorkspaceBundleSigner signer = new WorkspaceBundleSigner(props(root));
        ObjectNode body = mapper.createObjectNode();
        body.put("formatVersion", 1);
        body.put("projectCode", "demo");
        body.put("exportedAt", "2026-11-09T00:00:00Z");
        body.putArray("files");

        String sig = signer.sign(body);
        assertThat(sig).hasSize(64).isEqualTo(signer.sign(body));
        assertThat(signer.verify(body, sig)).isTrue();
        assertThat(signer.verify(body, sig.toUpperCase())).as("十六进制大小写不敏感").isTrue();
        assertThat(signer.verify(body, null)).isFalse();
        assertThat(signer.verify(body, "xyz")).isFalse();

        ObjectNode changed = body.deepCopy();
        changed.put("projectCode", "demo2");
        assertThat(signer.verify(changed, sig)).as("清单任一字段变化必须验签失败").isFalse();

        String flipped = (sig.charAt(0) == '0' ? "1" : "0") + sig.substring(1);
        assertThat(signer.verify(body, flipped)).isFalse();

        WorkspaceProperties other = new WorkspaceProperties();
        other.setRoot(root.toString());
        other.setSigningKey(KEY.substring(0, 62) + "00");
        assertThat(new WorkspaceBundleSigner(other).verify(body, sig)).as("换密钥必须验签失败").isFalse();
    }

    @Test
    @DisplayName("签名密钥只接受 64 位十六进制；缺失时失败而不是降级为无签名")
    void signingKeyMustBeHex64(@TempDir Path root) {
        WorkspaceProperties bad = new WorkspaceProperties();
        bad.setRoot(root.toString());
        bad.setSigningKey("not-a-key");
        assertThatThrownBy(() -> new WorkspaceBundleSigner(bad).sign(mapper.createObjectNode()))
                .isInstanceOf(com.qa.autotest.common.BusinessException.class)
                .hasMessageContaining("64 位十六进制");
    }

    @Test
    @DisplayName("扫描：只扫 .robot/.py；多规则逐条给行号；干净脚本零命中")
    void scanner() {
        ScriptSafetyScanner scanner = new ScriptSafetyScanner();
        List<String> hits = scanner.scan("bad.py", "import os\nos.system('id')\neval('1')\n");
        assertThat(hits).hasSize(2);
        assertThat(hits).anySatisfy(line -> assertThat(line).contains("第 2 行").contains("os.system"));
        assertThat(hits).anySatisfy(line -> assertThat(line).contains("第 3 行").contains("eval"));
        assertThat(scanner.scan("bad.py", "import os\nos.system('id')\nos.system('id')\n"))
                .as("同一规则只报一次，避免同一模式刷屏").hasSize(1);
        assertThat(scanner.scan("ok.robot", "*** Test Cases ***\n登录\n    Log    1\n")).isEmpty();
        assertThat(scanner.scan("README.txt", "os.system('id')"))
                .as("非脚本文件不扫描").isEmpty();
        assertThat(scanner.scan("BIG.ROBOT", "Evaluate    ${x}")).hasSize(1);
        assertThat(scanner.scannable("a.py")).isTrue();
        assertThat(scanner.scannable("a.txt")).isFalse();
        assertThat(scanner.scannable(null)).isFalse();
    }
}
