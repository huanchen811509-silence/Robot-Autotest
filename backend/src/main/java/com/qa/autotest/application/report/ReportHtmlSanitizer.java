package com.qa.autotest.application.report;

/**
 * 快照 HTML 的服务端清洗（v5.14.0 / W64-05 / NFR-v5.14-06）。
 *
 * <p>预览走 {@code iframe sandbox=""}（既不给 {@code allow-same-origin} 也不给
 * {@code allow-scripts}），但历史库里可能存着早期未转义的 {@code snapshot_html}。
 * 只靠 sandbox 是「运行时挡住」，把清洗规则显式写在服务端是「数据出口挡住」——
 * 两层都要有，且规则必须能单测。
 *
 * <p>清洗规则（登记在 PRD §13 / W7 文档）：
 * <ol>
 *   <li>整块删除 {@code script/style/iframe/object/embed}（含标签内文本）；</li>
 *   <li>删除所有 {@code on*} 事件属性；</li>
 *   <li>把 {@code javascript: / vbscript: / data:} 协议改成 {@code blocked:}；</li>
 *   <li>删除 {@code link/meta/base/form/input/svg/math} 等可外链或可提交的标签（保留其文本）；</li>
 *   <li>其余标签（{@code dl/dt/dd/h1/h2/p/table/tr/td/span/div} 等）原样保留，文本不改写。</li>
 * </ol>
 *
 * <p>这是**保守白名单外的黑名单**：报告内容是平台自己生成的，不是用户富文本编辑器；
 * 因此不需要完整 HTML 解析器，但必须保证「脚本进不了 iframe」。规则变更须同步改单测与文档。
 */
public final class ReportHtmlSanitizer {

    private ReportHtmlSanitizer() {
    }

    private static final String[] BLOCK_TAGS = {"script", "style", "iframe", "object", "embed"};

    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw;
        for (String tag : BLOCK_TAGS) {
            s = s.replaceAll("(?is)<" + tag + "\\b[^>]*>.*?</" + tag + "\\s*>", "");
            s = s.replaceAll("(?is)<" + tag + "\\b[^>]*/?>", "");
        }
        s = s.replaceAll("(?i)\\s+on[a-z0-9_-]*\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        s = s.replaceAll("(?i)(javascript|vbscript|data)\\s*:", "blocked:");
        s = s.replaceAll("(?i)</?(?:link|meta|base|form|input|svg|math)[^>]*>", "");
        return s;
    }
}
