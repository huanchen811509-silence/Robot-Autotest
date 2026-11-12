package com.qa.autotest.application.kernel.pytest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * pytest 步骤与命令证据抽取（FR-v5.1-08 / W51-12，验收 A22/A23）。
 *
 * <p><b>真源是 junit 的捕获输出，不是框架的 framework.log</b>。理由：
 * <ol>
 *   <li>{@code framework.log} 是<b>会话级单文件</b>，内部没有用例边界标记
 *       （{@code 资源池选定} 行只在使用例申请资源时出现，不是每个用例都有）。
 *       靠行序 + 执行顺序去切，遇到并行/重试就错位，属于「看起来对」的假证据。</li>
 *   <li>junit 的 {@code <testcase>} 天然按用例分组。<b>前提是日志被捕获</b>——
 *       用例库 {@code addopts} 带 {@code -s}，所以 {@link PytestNicKernelCommand}
 *       在命令行末尾追加 {@code --capture=fd} 覆盖它。没有这一步，
 *       {@code <system-out>} 全是空的，抽取结果会如实标记 {@code captured=false}，
 *       页面提示「本次未捕获到用例日志」，而不是编一棵空树出来。</li>
 * </ol>
 *
 * <p><b>识别的是框架已声明的两种结构化日志行</b>（{@code tests/conftest.py} 的
 * {@code StepReporter.check}）：
 * <ul>
 *   <li>{@code [明细] <标签>：<取值原文>} —— 步骤明细；
 *       {@code [明细] PCI BDF=0000:31:00.0} 这种没有标签的写法也要认（标签留空）；</li>
 *   <li>{@code [判定] <标签>：通过} / {@code [判定] <标签>：不通过（原因；原因）} —— 判定结论。</li>
 * </ul>
 * Plan-only 模式下框架打的是 {@code [明细] <标签>：Plan-only 无真实回显，本步骤取值打印与判定跳过}，
 * 记为 {@code SKIP} 而不是 {@code PASS} —— 没真实执行就不该算通过。
 */
@Service
public class PytestStepExtractor {

    /** 明细标记（框架 {@code logging.info("[明细] ...")}）。 */
    public static final String DETAIL = "[明细]";
    /** 判定标记（框架 {@code logging.info("[判定] ...")}）。 */
    public static final String VERDICT = "[判定]";

    public static final String KIND_DETAIL = "DETAIL";
    public static final String KIND_VERDICT = "VERDICT";
    public static final String KIND_FAILURE = "FAILURE";
    public static final String KIND_COMMAND = "COMMAND";

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_SKIP = "SKIP";

    /**
     * 单条步骤。
     *
     * @param logLine    该行在捕获文本里的行号（1 起），便于回原文核对；命令行为 0
     * @param attachment 证据附件相对路径（命令的 stdout 文件）；无附件为 null。
     *                   存相对路径不存绝对路径：产物会被归档搬到别的机器，绝对路径到那边一定失效
     */
    public record Step(int seq, String kind, String label, String content, String status,
                       long logLine, String attachment) {

        public Step(int seq, String kind, String label, String content, String status,
                    long logLine) {
            this(seq, kind, label, content, status, logLine, null);
        }
    }

    /**
     * 一个用例的步骤集合。
     *
     * @param captured junit 里是否真的拿到了捕获文本；false 表示步骤为空是因为「没捕获」，
     *                 不是因为「用例没写步骤」——页面必须区分这两种情况。
     */
    public record CaseSteps(String nodeId, String matchKey, List<Step> steps, boolean captured) {

        public long failCount() {
            return steps.stream().filter(s -> STATUS_FAIL.equals(s.status())).count();
        }
    }

    /**
     * 从一批 junit 文件抽取。批次顺序即执行顺序，合并后保持稳定。
     */
    public List<CaseSteps> fromJunit(List<Path> junitFiles) {
        if (junitFiles == null || junitFiles.isEmpty()) {
            return List.of();
        }
        List<CaseSteps> result = new ArrayList<>();
        for (Path file : junitFiles) {
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            result.addAll(parse(file));
        }
        return List.copyOf(result);
    }

    private List<CaseSteps> parse(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 与 PytestResultNormalizer 同一套安全设置：禁 DOCTYPE、禁外部实体。
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(in);
            NodeList nodes = doc.getElementsByTagName("testcase");
            List<CaseSteps> cases = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                String classname = node.getAttribute("classname");
                String name = node.getAttribute("name");
                String capturedText = capturedOf(node);
                boolean captured = !capturedText.isBlank();
                List<Step> steps = new ArrayList<>(fromCaptured(capturedText));
                Step failure = failureStep(node, steps.size() + 1);
                if (failure != null) {
                    steps.add(failure);
                }
                String nodeId = classname.isEmpty() ? name : classname + "::" + name;
                cases.add(new CaseSteps(nodeId, PytestResultNormalizer.matchKey(classname, name),
                        List.copyOf(steps), captured));
            }
            return cases;
        } catch (Exception e) {
            throw new IllegalStateException("抽取 pytest 步骤失败: " + file + " — " + e.getMessage(), e);
        }
    }

    /**
     * 把 testcase 下所有 {@code <system-out>} / {@code <system-err>} 拼起来。
     *
     * <p>两个标签都要读：框架的 {@code logging.StreamHandler()} 默认写 <b>stderr</b>，
     * pytest 在 xunit2 下把 capstdout 放 system-out、capstderr 放 system-err。
     * 只读 system-out 会一条明细都拿不到——这是最容易踩的坑。
     *
     * <p><b>同时要按内容去重</b>：pytest 按阶段（setup / call / teardown）各写一份捕获块，
     * 一个在 call 阶段被 skip 的用例会产生两个阶段报告，两份 {@code <system-err>} 内容
     * <b>逐字相同</b>。不去重的话步骤树会整体翻倍（实测 3 条明细变成 6 条），
     * 而「条数翻倍」看起来像用例写了两遍，极难定位。只丢弃与前面完全相同的块，
     * 不做「模糊去重」——真的写了两次相同内容仍会保留两条。
     */
    private static String capturedOf(Element testcase) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String tag : new String[] {"system-out", "system-err"}) {
            NodeList list = testcase.getElementsByTagName(tag);
            for (int i = 0; i < list.getLength(); i++) {
                Node item = list.item(i);
                String text = item.getTextContent();
                if (text == null || text.isBlank() || !seen.add(text)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /** 断言失败本身也是一步：否则页面只看到「判定都通过」却不知道用例为什么红。 */
    private static Step failureStep(Element testcase, int seq) {
        for (String tag : new String[] {"failure", "error"}) {
            NodeList list = testcase.getElementsByTagName(tag);
            if (list.getLength() == 0) {
                continue;
            }
            Element first = (Element) list.item(0);
            String message = first.getAttribute("message");
            String content = message != null && !message.isBlank()
                    ? message : first.getTextContent();
            return new Step(seq, KIND_FAILURE, tag, truncate(trimToNull(content)), STATUS_FAIL, 0);
        }
        return null;
    }

    /**
     * 纯函数：捕获文本 → 步骤表。无 IO、无 Spring，单测可直喂字符串。
     *
     * <p>每行只在<b>出现标记之后</b>解析，因此日志格式化前缀（时间戳 / 级别 / logger 名）
     * 不影响结果，用例自己在明细里写的方括号也不影响。
     */
    public static List<Step> fromCaptured(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Step> steps = new ArrayList<>();
        String[] lines = text.split("\\R");
        int seq = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int detailAt = line.indexOf(DETAIL);
            int verdictAt = line.indexOf(VERDICT);
            if (detailAt < 0 && verdictAt < 0) {
                continue;
            }
            boolean isVerdict = verdictAt >= 0 && (detailAt < 0 || verdictAt < detailAt);
            String body = line.substring(isVerdict ? verdictAt + VERDICT.length()
                    : detailAt + DETAIL.length()).trim();
            if (body.isEmpty()) {
                continue;
            }
            String label = labelOf(body);
            String detail = label.isEmpty() ? body : body.substring(body.indexOf('：') + 1).trim();
            Step step = isVerdict
                    ? verdictStep(++seq, label, detail, i + 1)
                    : detailStep(++seq, label, detail, i + 1);
            steps.add(step);
        }
        return List.copyOf(steps);
    }

    private static Step detailStep(int seq, String label, String detail, long logLine) {
        boolean planOnly = detail.contains("Plan-only");
        return new Step(seq, KIND_DETAIL, label, detail, planOnly ? STATUS_SKIP : null, logLine);
    }

    /** 判定行分三种：通过 / 不通过（原因…）/ 「必需字段 N 项均有有效回显」（也属通过）。 */
    private static Step verdictStep(int seq, String label, String detail, long logLine) {
        if (detail.startsWith("不通过")) {
            return new Step(seq, KIND_VERDICT, label, parenContent(detail), STATUS_FAIL, logLine);
        }
        if (detail.startsWith("通过")) {
            return new Step(seq, KIND_VERDICT, label, "", STATUS_PASS, logLine);
        }
        // 「必需字段 4 项均有有效回显（a、b…）」是准入检查，不是业务判定，但不该算失败。
        return new Step(seq, KIND_VERDICT, label, detail, STATUS_PASS, logLine);
    }

    /** {@code 不通过（a；b）} → {@code a；b}。没有括号就原样返回。 */
    private static String parenContent(String detail) {
        int open = detail.indexOf('（');
        int close = detail.lastIndexOf('）');
        if (open >= 0 && close > open) {
            return detail.substring(open + 1, close).trim();
        }
        return detail;
    }

    /**
     * 取标签：{@code 标签：取值} 的 {@code 标签}。
     *
     * <p>四个否定条件都是实测撞出来的：取值里常出现 {@code =}（{@code PCI BDF=...}）、
     * 空格（{@code 必需字段 4 项...}）、以及本身就是纯中文长句。命中任一即视为「无标签」，
     * 整行留给取值——宁可标签空，也不要把取值的前半截切成假标签。
     */
    private static String labelOf(String body) {
        int colon = body.indexOf('：');
        if (colon <= 0) {
            return "";
        }
        String candidate = body.substring(0, colon).trim();
        if (candidate.isEmpty() || candidate.length() > 48) {
            return "";
        }
        if (candidate.contains("=") || candidate.contains(" ") || candidate.contains("，")) {
            return "";
        }
        return candidate;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 4000 ? text : text.substring(0, 4000) + "…（已截断）";
    }

    /**
     * 命令证据：读框架的 {@code cases/<node>/raw/*.commands.jsonl}。
     *
     * <p>每行一个 JSON（{@code command_id / command / purpose / expected_observation /
     * stdout_file / stderr_file}），由框架 {@code RawSessionWriter} 写入，与 {@code .raw.log}
     * 同目录。这里只取一条命令一行，{@code stdout} 用相对路径引用、不把正文灌进 DB。
     *
     * <p>参数 {@code rawDir} 不存在时返回空表（不抛）：命令证据缺失不该阻断步骤树入库。
     */
    public List<Step> commandsOf(Path rawDir, int startSeq) {
        if (rawDir == null || !Files.isDirectory(rawDir)) {
            return List.of();
        }
        List<Step> steps = new ArrayList<>();
        int seq = startSeq;
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(rawDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".commands.jsonl"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            return List.of();
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    var node = mapper.readTree(line);
                    String command = node.path("command").asText("");
                    String purpose = node.path("purpose").asText("");
                    String commandId = node.path("command_id").asText("");
                    String stdoutFile = node.path("stdout_file").asText("");
                    String content = purpose.isBlank() ? command
                            : purpose + " :: " + command;
                    steps.add(new Step(seq++, KIND_COMMAND, commandId, truncate(content), null, 0,
                            stdoutFile.isBlank() ? null : rawRelative(stdoutFile)));
                } catch (IOException ignored) {
                    // 单行坏了不影响其他命令；坏行的证据会在计数里体现为缺失。
                }
            }
        }
        return List.copyOf(steps);
    }

    /**
     * 把 stdout 文件写成相对 raw 目录的路径。
     *
     * <p>不用绝对路径：run 的产物会被归档/搬到别的机器，绝对路径到了那边一定失效。
     */
    private static String rawRelative(String stdoutFile) {
        return "commands/" + stdoutFile;
    }

    /** 便捷：把 {@link CaseSteps} 按 matchKey 建索引，供入库时挂 test_result。 */
    public static Map<String, CaseSteps> byMatchKey(List<CaseSteps> cases) {
        Map<String, CaseSteps> index = new LinkedHashMap<>();
        for (CaseSteps item : cases) {
            index.putIfAbsent(item.matchKey(), item);
        }
        return index;
    }
}
