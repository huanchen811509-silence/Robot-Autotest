package com.qa.autotest.application.caseasset;

/**
 * 用例 → 测试脚本的展示视图（v5.11.0 / W61-11 / FR-v5.11-05）。
 *
 * <p><b>为什么后端算文件名</b>：列表、详情、用例集成员三处显示同一个用例时必须给出
 * <b>逐字相同</b>的字符串（FR-05 判据 6）。若前端各自切路径，三处就会因为实现细节
 * （谁处理了反斜杠、谁截了绝对前缀）而出现「同一条用例三个路径」。
 * 因此规范化与取名只在这里做一次，前端只负责展示与复制。
 *
 * <p><b>不造假的路径</b>：RF 用例当前没有 {@code test_case ↔ 脚本} 的关联列，
 * 显示 {@link #NOT_APPLICABLE} 而不是去猜一条最像的路径——猜出来的路径比没有更危险。
 *
 * @param nodeId           pytest nodeid（详情里和路径一起显示，便于直接定位函数）
 * @param bindingState     BOUND_VERIFIED / BOUND_UNVERIFIED / UNBOUND / NOT_APPLICABLE
 * @param kernel           实际命中的内核（未绑定时为项目内核）
 * @param otherScriptCount 除首选绑定外还有几条（多内核场景，UI 折叠为「+N」）
 * @param absoluteSource   原始值是否为绝对路径（历史脏数据；已截断为仓库内相对段）
 */
public record CaseScriptView(String scriptPath, String scriptName, String nodeId,
                             String bindingState, String kernel, int otherScriptCount,
                             boolean absoluteSource) {

    /** 已绑定且经人工校验。 */
    public static final String BOUND_VERIFIED = "BOUND_VERIFIED";
    /** 已绑定但未校验（同步自动解析的默认态）。 */
    public static final String BOUND_UNVERIFIED = "BOUND_UNVERIFIED";
    /** 该内核支持脚本绑定，但这条用例没有绑定记录。 */
    public static final String UNBOUND = "UNBOUND";
    /** 该内核不支持脚本绑定（RF 侧无关联列），显示「不适用」。 */
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    /** 目前只有 pytest 链路写 {@code case_script_binding}。 */
    private static final String KERNEL_PYTEST = "pytest";

    /** 视为「仓库内根目录」的目录名：绝对路径截断时从这些段开始取。 */
    private static final String[] REPO_ROOTS = {"tests/", "cases/", "suites/", "robot/", "scripts/"};

    /** 空绑定的两种形态：支持脚本 → 未绑定；不支持 → 不适用。 */
    public static CaseScriptView empty(String kernel) {
        String state = supportsScript(kernel) ? UNBOUND : NOT_APPLICABLE;
        return new CaseScriptView(null, null, null, state, kernel, 0, false);
    }

    public static boolean supportsScript(String kernel) {
        return KERNEL_PYTEST.equalsIgnoreCase(kernel == null ? "" : kernel.trim());
    }

    /** 绑定行 → 展示视图；{@code path} / {@code verified} 来自 {@code case_script_binding}。 */
    public static CaseScriptView bound(String rawPath, String nodeId, boolean verified,
                                       String kernel, int otherScriptCount) {
        boolean absolute = isAbsolute(rawPath);
        String relative = normalizeRelative(rawPath);
        String state = verified ? BOUND_VERIFIED : BOUND_UNVERIFIED;
        return new CaseScriptView(relative, fileNameOf(relative), nodeId, state, kernel,
                otherScriptCount, absolute);
    }

    /** 是否绝对路径（POSIX 或 Windows 盘符）。 */
    public static boolean isAbsolute(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String p = raw.trim().replace('\\', '/');
        return p.startsWith("/") || p.matches("^[A-Za-z]:/.*");
    }

    /**
     * 绝对路径 → 仓库内相对段（FR-05 判据 7 与降级预案）。
     *
     * <p>相对路径原样返回（只统一分隔符）。绝对路径依次去掉盘符与根，
     * 再从命中 {@link #REPO_ROOTS} 的段开始截取；都命中不到时返回去掉前缀后的剩余段，
     * 由 UI 依据 {@code absoluteSource} 在 tooltip 里注明「原始值为绝对路径」。
     */
    public static String normalizeRelative(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String p = raw.trim().replace('\\', '/');
        if (!isAbsolute(p)) {
            return p;
        }
        p = p.replaceFirst("^[A-Za-z]:", "");
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        for (String root : REPO_ROOTS) {
            if (p.startsWith(root)) {
                return p;
            }
            int idx = p.indexOf("/" + root);
            if (idx >= 0) {
                return p.substring(idx + 1);
            }
        }
        return p;
    }

    /** 末段文件名（{@code cases/net/roce_bw.py} → {@code roce_bw.py}），保留扩展名。 */
    public static String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
