package com.qa.autotest.common.security;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全表面凭据脱敏（FR-v4.0-01）。
 *
 * <p>同一个实现服务报告 / 打印 / DOCX / API / 审计 / 运行日志 / 控制台 / 导出包等表面：
 * 任一处文本都先过 {@link #redact(String)}，导出前再用 {@link #assertClean(String, String)}
 * 做一次「命中即阻断」的兜底。写两份正则必然漂移，所以只允许本类持有规则。
 *
 * <p>覆盖三类真实出现过的写法：
 * <ol>
 *   <li>键值：{@code password=xxx} / {@code "token":"xxx"} / {@code MYSQL_PWD=xxx}；</li>
 *   <li>命令行短参：{@code -u root:secret} / {@code sshpass -p secret}
 *       / {@code --password=secret} / {@code --token secret}；</li>
 *   <li>URL 内嵌：{@code mysql://root:secret@host}；</li>
 * </ol>
 *
 * <p>不做「见 -p 就脱敏」这类猜测：{@code ssh -p 22} 里 -p 是端口，误脱敏会把
 * 证据命令改得不可复现。规则宁可窄一点，也不能把正常命令改坏。
 */
public final class SecretRedactor {

    /** 占位符：固定文本，便于扫描器判断与人工核对。 */
    public static final String MASK = "<redacted>";

    private static final List<Pattern> RULES = List.of(
            // 0) HTTP 认证方案必须先跑：按键值规则会把 "Bearer" 当值吃掉，令牌尾部反而漏出
            Pattern.compile("(?i)(\\b(?:Bearer|Basic|Digest)(?:\\s+))([A-Za-z0-9._~+/=-]+)"),
            // 1) 键值（含 JSON 引号形态）：password=xxx / "password":"xxx" / DB_PASSWORD=xxx
            //    scheme 词（Bearer/Basic/Digest）已由 0) 处理，这里排除，避免二次脱敏成
            //    "Authorization: <redacted> <redacted>"（把方案名也抹了，反而不像原文）。
            Pattern.compile(
                    "(?i)(\"?(?:[A-Z0-9_]*(?:password|passwd|pwd|token|secret|api[_-]?key"
                            + "|credential|access[_-]?key|private[_-]?key|authorization"
                            // 审计历史里存在中文键名（口令 / 密码 / 密钥），迁移到本类后仍须覆盖
                            + "|口令|密码|令牌|密钥|凭据))\"?"
                            + "\\s*[=:]\\s*)"
                            // 认证方案词必须排除在「值」之外：若把 lookahead 放在分组前，
                            // 正则会回溯 `\s*` 让 "Bearer" 落进值分组，二次脱敏成
                            // "Authorization: <redacted> <redacted>"（方案名被抹，不像原文）。
                            + "((?!(?:bearer|basic|digest)\\b)"
                            + "(?:\"[^\"]*\"|'[^']*'|[^,;\\s}]+))"),
            // 3) -u/--user 后带冒号的口令（保留用户名）
            Pattern.compile("(?i)((?:-u|--user)\\s+[^:\\s]+:)(\\S+)"),
            // 4) --password / --passwd / --token / --secret 的等号或空格形态
            Pattern.compile("(?i)(--(?:password|passwd|token|secret)(?:=|\\s+))(\\S+)"),
            // 5) sshpass -p
            Pattern.compile("(?i)(sshpass\\s+-p\\s+)(\\S+)"),
            // 6) URL 内嵌 user:pass@：第 3 组保留 @，第三组在本类里显式拼回
            Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://[^\\s/:@]+:)([^\\s@]+)(@)"));

    private SecretRedactor() {
    }

    /** 把凭据值替换为 {@link #MASK}；幂等（已脱敏文本再跑一次不变）。 */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern rule : RULES) {
            Matcher matcher = rule.matcher(result);
            StringBuilder out = new StringBuilder(result.length());
            while (matcher.find()) {
                String value = matcher.group(2);
                String masked = value;
                if (value.length() >= 2
                        && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                        && value.charAt(value.length() - 1) == value.charAt(0)) {
                    // 保留引号，避免把 JSON / 日志里的结构引号一起删掉后产生非法片段
                    masked = value.charAt(0) + MASK + value.charAt(0);
                } else {
                    masked = MASK;
                }
                StringBuilder piece = new StringBuilder(matcher.group(1)).append(masked);
                if (matcher.groupCount() >= 3) {
                    piece.append(matcher.group(3));
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(piece.toString()));
            }
            matcher.appendTail(out);
            result = out.toString();
        }
        return result;
    }

    /**
     * 是否仍含明文凭据：以「脱敏后是否变化」为准，不做二次猜测。
     *
     * <p>幂等性保证 {@code redact(redact(x)) == redact(x)}，因此这里只会对未脱敏文本为真。
     */
    public static boolean hasLeak(String text) {
        return text != null && !redact(text).equals(text);
    }

    /**
     * 导出 / 写入前的兜底闸门：检测到明文凭据直接阻断，而不是「先导出再提醒」。
     *
     * @param surface 出问题的表面名（report / docx / api / audit / log / bundle ...），进错误消息
     */
    public static String assertClean(String text, String surface) {
        if (hasLeak(text)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "脱敏闸门阻断：surface=" + surface + " 仍含明文凭据");
        }
        return text;
    }

    /** 供日志 / 错误消息使用的摘要：只给规则命中次数，不回显内容本身。 */
    public static int leakCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Pattern rule : RULES) {
            Matcher matcher = rule.matcher(text);
            while (matcher.find()) {
                count++;
            }
        }
        return count;
    }
}
