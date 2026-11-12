package com.qa.autotest.application.execution.monitor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 形态级凭据掩码（W53-02 / R3）。
 *
 * <p>和 {@code SecretRedactor} 的分工要分清，否则两边会各自漂移：
 * <ul>
 *   <li>{@code SecretRedactor} 负责「已知凭据<b>值</b>」——它手里有配置里的口令原文，按值抹；</li>
 *   <li>本类负责「凭据<b>形态</b>」——{@code -pXXX}、{@code --password=XXX}、{@code token=XXX}，
 *       即使那个值从未出现在配置里（例如用户在脚本里写死的测试口令），也必须抹掉。</li>
 * </ul>
 * 两者串联使用：先抹形态，再按值抹。只做前者会漏掉配置里的值；只做后者会漏掉脚本里写死的值。
 *
 * <p><b>{@code -p} 的例外是刻意加的</b>：{@code ssh -p 2222} 里 {@code -p} 是端口不是口令。
 * 因此规则写成「{@code -p} 后面紧跟的<b>不是数字</b>才算口令」。误抹端口的代价是日志少一个端口号，
 * 漏抹口令的代价是凭据泄漏——两者不对等，所以天平偏向多抹。
 */
final class SensitiveMask {

    /** 关键字表：命中即说明这个参数/片段携带凭据。 */
    private static final Pattern KEYWORD = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key"
                    + "|private[_-]?key|community|credential|passphrase)");

    /** {@code -pXXX}（XXX 不以数字开头，排除 ssh 端口）。 */
    private static final Pattern SHORT_P = Pattern.compile("(?<!\\S)-p(?![0-9])(\\S+)");

    /** {@code --password=XXX} / {@code --token XXX}。 */
    private static final Pattern LONG_FLAG = Pattern.compile(
            "(?i)(--(?:password|passwd|token|secret|api-key|api_key|access-key|community"
                    + "|passphrase|credential)(?:=|\\s+))(\\S+)");

    /** {@code password=XXX} / {@code PASSWORD=XXX}。 */
    private static final Pattern ASSIGN = Pattern.compile(
            "(?i)((?:^|\\s)(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key"
                    + "|community|passphrase|credential)\\s*=\\s*)(\\S+)");

    private static final String MASK = "***";

    private SensitiveMask() {
    }

    /** 该文本是否携带凭据形态（用于置 {@code sensitive} 标记）。 */
    static boolean looksSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return KEYWORD.matcher(text).find()
                || SHORT_P.matcher(text).find();
    }

    /** 抹掉形态级凭据，保留其余内容。 */
    static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String value = replaceGroup(SHORT_P, text, "-p" + MASK, 0);
        value = replaceGroup(LONG_FLAG, value, null, 1);
        value = replaceGroup(ASSIGN, value, null, 1);
        return value;
    }

    /**
     * 把命中片段的「值部分」换成掩码。
     *
     * @param keepGroup 非 null 时整段替换为它（用于 {@code -pXXX}）；null 时保留第 1 组、掩码第 2 组
     */
    private static String replaceGroup(Pattern pattern, String text, String keepGroup,
                                       int groupKept) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            sb.append(text, last, matcher.start());
            if (keepGroup != null) {
                sb.append(keepGroup);
            } else {
                sb.append(matcher.group(groupKept)).append(MASK);
            }
            last = matcher.end();
        }
        sb.append(text.substring(last));
        return sb.toString();
    }
}
