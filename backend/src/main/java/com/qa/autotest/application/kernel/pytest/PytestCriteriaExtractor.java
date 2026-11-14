package com.qa.autotest.application.kernel.pytest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * 从 pytest 的 junit XML 里提取「用例 → 判据原文」（v5.7.0 / FR-v5.7-12，复核 W57-50）。
 *
 * <p><b>为什么单独扫一遍 XML</b>：{@code PytestKernel.normalize} 只要结论与命令三列，
 * 判据块在被它丢弃之前就该被取走。改 {@code NormalizedCaseResult} 会让 RF 侧也跟着抖动；
 * 单独扫一次只多一次本地文件读，且失败不影响执行事实。
 *
 * <p><b>归属口径</b>：只认 {@code <testcase>} 内部的 {@code <system-out>}——
 * testsuite 级的 {@code <system-out>} 是所有用例混在一起的输出，无法归属到具体用例，
 * 一律计入 {@code unassigned} 并**不猜**它属于谁。
 *
 * <p><b>容错</b>：任何文件解析失败只跳过该文件（返回已收集部分），不抛给调用方——
 * 判据采集失败不能把一次已经跑完的执行判成失败。
 */
public final class PytestCriteriaExtractor {

    /**
     * @param byCaseName 用例名（junit {@code testcase@name}，含参数化后缀）→ 判据原文
     * @param files      实际解析成功的文件数
     * @param unassigned 无法归属到具体用例的 {@code system-out} 段数（仅用于如实报数）
     */
    public record Extraction(Map<String, String> byCaseName, int files, int unassigned) {

        public static Extraction empty() {
            return new Extraction(Map.of(), 0, 0);
        }
    }

    private PytestCriteriaExtractor() {
    }

    /** 扫目录下所有 {@code *.xml}（不递归）。目录不存在或为空时返回空结果，不抛异常。 */
    public static Extraction scan(Path artifactDir) {
        if (artifactDir == null || !Files.isDirectory(artifactDir)) {
            return Extraction.empty();
        }
        List<Path> xmls;
        try (Stream<Path> stream = Files.list(artifactDir)) {
            xmls = stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xml"))
                    .sorted().toList();
        } catch (Exception e) {
            return Extraction.empty();
        }
        Map<String, String> byName = new LinkedHashMap<>();
        int files = 0;
        int unassigned = 0;
        for (Path xml : xmls) {
            try (InputStream in = Files.newInputStream(xml)) {
                int[] counter = new int[1];
                readOne(in, byName, counter);
                unassigned += counter[0];
                files++;
            } catch (Exception ignored) {
                // 单个文件坏掉不影响其它文件；判据缺了会在 criteria_missing 里体现。
            }
        }
        return new Extraction(Map.copyOf(byName), files, unassigned);
    }

    private static void readOne(InputStream in, Map<String, String> byName, int[] unassigned)
            throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // 禁用外部实体：artifact 目录里的文件可能来自被测环境，不能让它读取本机文件。
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        XMLStreamReader reader = factory.createXMLStreamReader(in);
        String currentCase = null;
        StringBuilder buffer = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("testcase".equals(local)) {
                        currentCase = attribute(reader, "name");
                    } else if ("system-out".equals(local)) {
                        if (currentCase == null) {
                            unassigned[0]++;
                        } else {
                            buffer = new StringBuilder();
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) {
                    if (buffer != null) {
                        buffer.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("system-out".equals(local) && buffer != null) {
                        String text = buffer.toString();
                        if (!text.isBlank()) {
                            // 同名用例（参数化重复）后出现的覆盖前面：后一轮是最终事实。
                            byName.put(currentCase, text);
                        }
                        buffer = null;
                    } else if ("testcase".equals(local)) {
                        currentCase = null;
                    }
                }
            }
        } finally {
            reader.close();
        }
    }

    private static String attribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }
}
