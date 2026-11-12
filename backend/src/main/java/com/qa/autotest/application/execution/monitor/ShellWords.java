package com.qa.autotest.application.execution.monitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 shell 分词（W53-02 命令集合用）。
 *
 * <p>为什么不用现成库：只有两个需求——按空白切分、尊重引号。引入 `bash -c` 会让
 * 「分析一条日志」变成「执行一条命令」，那是对不可信文本开洞；引入完整 parser
 * 又会把 shell 语法（管道/重定向/子命令）当成必须支持的能力，而命令集合只需要
 * {@code argv} 用于分类与 diff，管道拆开的每一段本身不是一条命令。
 *
 * <p>因此规则写死三条：
 * <ol>
 *   <li>空白分隔（连续空白算一个）；</li>
 *   <li>单引号内不做任何转义，双引号内支持 {@code \"}；</li>
 *   <li>引号不闭合时<b>把剩余全部当同一个参数</b>，不抛异常——日志里的命令经常被
 *       终端宽度截断，为此丢整条记录不值得。</li>
 * </ol>
 */
public final class ShellWords {

    private ShellWords() {
    }

    public static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        if (line == null || line.isBlank()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < line.length()) {
                    current.append(line.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                started = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (started || current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
                continue;
            }
            current.append(c);
        }
        if (started || current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }
}
