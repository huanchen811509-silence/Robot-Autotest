package com.qa.autotest.application.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 导入脚本的恶意模式扫描（FR-v4.1-02 ⑩）。
 *
 * <p>只做「提示 + 隔离」：命中不等于一定恶意，导入者显式确认后才写库；
 * 扫描绝不静默放行，也绝不因命中而整包拒绝（其余文件仍可导入）。</p>
 */
@Component
public class ScriptSafetyScanner {

    private record Rule(Pattern pattern, String reason) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("os\\.system"), "直接调用 shell（os.system）"),
            new Rule(Pattern.compile("subprocess\\.(Popen|run|call|check_output)"), "启动外部进程（subprocess）"),
            new Rule(Pattern.compile("Runtime\\.getRuntime|ProcessBuilder"), "启动外部进程（Java Runtime/ProcessBuilder）"),
            new Rule(Pattern.compile("\\bexec\\s*\\("), "执行动态代码（exec）"),
            new Rule(Pattern.compile("\\beval\\s*\\("), "执行动态代码（eval）"),
            new Rule(Pattern.compile("__import__"), "动态导入模块（__import__）"),
            new Rule(Pattern.compile("import\\s+socket|from\\s+socket\\s+import"), "直接建立网络套接字（socket）"),
            new Rule(Pattern.compile("ftplib|paramiko\\.Transport"), "原始传输协议调用（ftplib/paramiko）"),
            new Rule(Pattern.compile("shutil\\.rmtree"), "递归删除目录（shutil.rmtree）"),
            new Rule(Pattern.compile("(?i)evaluate"), "Robot Evaluate 可执行内联 Python"));

    /** @return 命中说明（含规则原因），空表示未命中；扫描只对 .robot/.py 生效。 */
    public List<String> scan(String path, String content) {
        List<String> findings = new ArrayList<>();
        if (!scannable(path) || content == null || content.isEmpty()) {
            return findings;
        }
        String[] lines = content.split("\n", -1);
        for (Rule rule : RULES) {
            for (int i = 0; i < lines.length; i++) {
                if (rule.pattern().matcher(lines[i]).find()) {
                    findings.add("第 " + (i + 1) + " 行：" + rule.reason());
                    break;
                }
            }
        }
        return findings;
    }

    public boolean scannable(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".robot") || lower.endsWith(".py");
    }
}
