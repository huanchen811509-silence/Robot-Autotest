package com.qa.autotest.application.execution;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * output.xml 轻量探测：能否解析、test 节点数量、真实三态统计、以及是否存在单用例超时。
 * 禁用 DTD 与外部实体。
 */
public final class OutputXmlProbe {

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_SKIP = "SKIP";

    /**
     * 逐用例三态统计（v5.24.0 / 执行结论口径修复）。
     *
     * <p>为什么不用 {@code <statistics>}：output.xml 的 {@code total} 下同时有
     * {@code All Tests} 与 {@code Critical Tests} 两条 {@code <stat>}，前者才是全量；
     * 而逐 {@code <test>} 取直接子 {@code <status>} 既不受 critical 标记影响，也不必依赖
     * {@code statistics} 段是否被写全（被 SIGKILL 的分片经常缺这一段）。
     *
     * <p>状态归并：{@code PASS} → passed，{@code FAIL} → failed，
     * {@code SKIP} / {@code NOT RUN} → skipped。<b>NOT RUN 计入 skipped 而不是 failed</b>：
     * 它表示「因前置失败未执行」，与「执行了但断言不过」是两件事，混进 failed 会虚高失败率。
     */
    public Stats stats(Path xml) {
        if (xml == null || !Files.exists(xml)) {
            return Stats.unknown(0);
        }
        try {
            Document document = parse(xml);
            NodeList tests = document.getElementsByTagName("test");
            int passed = 0;
            int failed = 0;
            int skipped = 0;
            for (int i = 0; i < tests.getLength(); i++) {
                String status = ownStatus((Element) tests.item(i));
                switch (status) {
                    case STATUS_PASS -> passed++;
                    case STATUS_FAIL -> failed++;
                    case STATUS_SKIP, "NOT RUN" -> skipped++;
                    default -> skipped++; // 未知状态按未执行处理：不虚报通过，也不虚报失败
                }
            }
            return new Stats(passed + failed + skipped, passed, failed, skipped);
        } catch (Exception ex) {
            return Stats.unknown(0);
        }
    }

    /**
     * 取 {@code <test>} 的<b>直接子</b> {@code <status>}。
     *
     * <p>不能用 {@code getElementsByTagName("status")}：{@code <test>} 内部的关键字、
     * {@code <if>}、{@code <for>} 分支都各有一层 {@code <status>}，那样会把一条用例数成十几条。
     */
    private static String ownStatus(Element test) {
        NodeList children = test.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "status".equals(node.getNodeName())) {
                String value = ((Element) node).getAttribute("status");
                return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    public Probe probe(Path xml) {
        if (xml == null || !Files.exists(xml)) {
            return new Probe(false, 0, "文件不存在");
        }
        try {
            Document document = parse(xml);
            int total = document.getElementsByTagName("test").getLength();
            return new Probe(true, total, null);
        } catch (Exception ex) {
            return new Probe(false, 0, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * 单用例超时条数（FR-v0.4-24）。
     *
     * <p>RF 命中 {@code Test Timeout} 时会写出固定文案
     * {@code "Test timeout 30 seconds exceeded."}，落在 test 的 {@code status} 下 {@code msg} 节点。
     * 解析失败或文件不存在返回 0（超时判定不能反过来把正常运行判成超时）。
     */
    public int timedOutTests(Path xml) {
        if (xml == null || !Files.exists(xml)) {
            return 0;
        }
        try {
            Document document = parse(xml);
            NodeList messages = document.getElementsByTagName("msg");
            int count = 0;
            for (int i = 0; i < messages.getLength(); i++) {
                String text = messages.item(i).getTextContent();
                if (text != null && text.contains("Test timeout") && text.contains("exceeded")) {
                    count++;
                }
            }
            return count;
        } catch (Exception ex) {
            return 0;
        }
    }

    private Document parse(Path xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(xml.toFile());
    }

    public record Probe(boolean parsed, int totalTests, String error) {
    }

    /**
     * 逐用例三态统计。
     *
     * <p>{@code known=false} 表示「没读到任何用例状态」（文件缺失/不可解析），
     * 调用方此时应保留执行期估值或标记为未知，<b>绝不能把 0 当成「跑了 0 条全通过」</b>。
     */
    public record Stats(int total, int passed, int failed, int skipped) {

        public static Stats unknown(int total) {
            return new Stats(total, 0, 0, 0);
        }

        /** 是否拿到了可信的三态（有一条以上落定即可信，全 0 视为未知）。 */
        public boolean known() {
            return passed + failed + skipped > 0;
        }
    }
}
