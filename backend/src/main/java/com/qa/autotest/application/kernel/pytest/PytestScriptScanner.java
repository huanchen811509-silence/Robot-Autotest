package com.qa.autotest.application.kernel.pytest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 用例库脚本扫描（FR-v5.1-04 / 验收 A8–A10）。
 *
 * <p>只做一件事：把一个 git 工作区里的 pytest 脚本扫成「编号 → 脚本 + nodeid」候选，
 * 不碰数据库、不判业务。写库与计数归 {@link PytestBindingSyncService}。
 *
 * <p><b>为什么必须读 docstring 而不是只看文件名</b>：实测 61 个用例文件中，
 * 文件名与编号**恰好**能对上纯属命名约定，一旦有人写 {@code test_checksum_131.py}
 * 就会误绑到不存在的编号。而文件头 {@code 用例编号: IM-NET-131} 是框架规范
 * （{@code docs/standards/用例开发规范.md}）要求的，唯一可信。
 * 文件名只作为最后的降级手段，且会被标记为 {@code FILENAME} 供人工复核。
 *
 * <p>实测事实（2026-11-12，pytest-cases@main）：61 个用例文件全部带
 * {@code 用例编号:} 声明、全部只有一个 {@code def test_*}；唯一例外是
 * {@code tests/test_config_and_plan.py}（框架自测，9 个 test 函数、无编号），
 * 该文件按「无编号 = 不是用例」跳过，不计入绑定。
 */
public final class PytestScriptScanner {

    /** 用例编号声明：`用例编号: IM-NET-131` / `用例编号：IM-NET-131`（半角/全角冒号都要认）。 */
    private static final Pattern CASE_CODE = Pattern.compile(
            "用例编号\\s*[:：]\\s*([A-Za-z][A-Za-z0-9]*-[A-Za-z][A-Za-z0-9]*-\\d+)");

    /** 文件名降级：test_im_net_131.py → IM-NET-131。 */
    private static final Pattern FILE_CODE = Pattern.compile(
            "^test_([a-z]+)_([a-z]+)_(\\d+)\\.py$", Pattern.CASE_INSENSITIVE);

    /** 模块级 test 函数（顶格 `def test_...`；类内方法有缩进，不属于一个用例一条绑定的口径）。 */
    private static final Pattern TEST_FUNC = Pattern.compile("^def\\s+(test_\\w+)\\s*\\(", Pattern.MULTILINE);

    /** 扫描时整棵跳过的目录：产物、虚拟环境、缓存、git 元数据。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".venv", "venv", "__pycache__", "artifacts", "node_modules", ".pytest_cache");

    /** 只读前 8KB 找编号声明——文件头声明不会出现在更后面的位置。 */
    private static final int HEAD_BYTES = 8192;

    private PytestScriptScanner() {
    }

    /** 扫描结果：一条绑定候选。 */
    public record ScannedScript(String relativePath,
                                String caseCode,
                                String nodeId,
                                String scriptSha256,
                                String resolveSource,
                                List<String> testFunctions,
                                boolean requiresPeer) {
    }

    /**
     * 扫描仓库根下的 {@code tests/} 目录。仓库根不可读时抛 {@link IOException}，
     * 由调用方转成「仓库不可达」的拒绝理由，不静默返回空列表。
     */
    public static List<ScannedScript> scan(Path repoRoot) throws IOException {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            throw new IOException("仓库根不存在或不是目录: " + repoRoot);
        }
        Path testsDir = repoRoot.resolve("tests");
        Path scanRoot = Files.isDirectory(testsDir) ? testsDir : repoRoot;
        List<ScannedScript> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(scanRoot)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("test_") || !name.endsWith(".py")) {
                    continue;
                }
                if (skipped(repoRoot, file)) {
                    continue;
                }
                ScannedScript scanned = scanOne(repoRoot, file);
                if (scanned != null) {
                    result.add(scanned);
                }
            }
        }
        return result;
    }

    /** 单个文件扫描；不是用例（无编号声明且文件名不成形）时返回 null。 */
    public static ScannedScript scanOne(Path repoRoot, Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<String> functions = new ArrayList<>();
        Matcher fn = TEST_FUNC.matcher(content);
        while (fn.find()) {
            functions.add(fn.group(1));
        }
        if (functions.isEmpty()) {
            return null;
        }
        String relative = repoRoot.relativize(file).toString().replace('\\', '/');

        String code = null;
        String source = null;
        Matcher declared = CASE_CODE.matcher(head(content));
        if (declared.find()) {
            code = declared.group(1).toUpperCase(Locale.ROOT);
            source = "DOCSTRING";
        } else {
            Matcher byName = FILE_CODE.matcher(file.getFileName().toString());
            if (byName.matches()) {
                code = (byName.group(1) + "-" + byName.group(2) + "-" + byName.group(3))
                        .toUpperCase(Locale.ROOT);
                source = "FILENAME";
            }
        }
        if (code == null) {
            // 无编号声明、文件名也不成形 —— 是框架自测或工具脚本，不是用例。
            return null;
        }
        // nodeid 取第一个 test 函数：实测 61 个用例文件都是单函数，
        // 多函数文件（仅框架自测）已在上面被「无编号」拦掉。
        String nodeId = relative + "::" + functions.get(0);
        // 双端判定用**代码特征**而不是用例标题：实测双端用例（如 IM-NET-131）
        // 都有 `TRAFFIC_DIRECTION` 常量，单端用例（如 IM-FUN-020）用 `TARGET_SLOT`。
        // 这是可机检的事实，比按分类猜可靠。
        boolean requiresPeer = content.contains("TRAFFIC_DIRECTION");
        return new ScannedScript(relative, code, nodeId, sha256(file), source, functions,
                requiresPeer);
    }

    private static boolean skipped(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path part : rel) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String head(String content) {
        return content.length() <= HEAD_BYTES ? content : content.substring(0, HEAD_BYTES);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("JVM 缺少 SHA-256", e);
        }
    }
}
