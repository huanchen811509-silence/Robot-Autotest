package com.qa.autotest.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归守护：代码里 {@code FORCE INDEX (xxx)} / {@code USE INDEX (xxx)} 引用的索引，
 * 必须有某个正向迁移脚本创建过。
 *
 * <p>背景（EW32-10 容量压测真实发现）：{@code TestCaseRepositoryJdbc} 在默认 id 排序分支
 * 强制走 {@code idx_case_project_id}，但 V11_19 只建了 {@code idx_case_project_updated}。
 * 单测用 fake 仓储、EXPLAIN 用临时表，都碰不到真实表结构，于是每次用例列表请求都 500。
 * 这条测试把「代码强制的索引名」与「迁移建过的索引名」对账，避免同类缺陷再次只在压测里暴露。
 */
class ForceIndexContractTest {

    private static final Pattern FORCED = Pattern.compile(
            "FORCE\\s+INDEX\\s*\\(\\s*`?([A-Za-z0-9_]+)`?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATED = Pattern.compile(
            "(?:CREATE\\s+(?:UNIQUE\\s+)?INDEX|ADD\\s+(?:UNIQUE\\s+)?(?:INDEX|KEY))\\s+`?([A-Za-z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);
    /** 建表语句里的行内 `KEY idx_x (...)` / `INDEX idx_x (...)` 同样算「迁移创建过」。 */
    private static final Pattern INLINE_CREATED = Pattern.compile(
            "(?:^|[,\\n(])\\s*(?:INDEX|KEY)\\s+`?([A-Za-z0-9_]+)`?\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    @Test
    void 强制索引必须由迁移脚本创建() throws IOException {
        Path migrations = locateMigrations();
        Set<String> created = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(migrations)) {
            files.filter(path -> path.toString().endsWith(".sql"))
                    .sorted()
                    .forEach(path -> {
                        String text = read(path);
                        created.addAll(matches(text, CREATED));
                        created.addAll(matches(text, INLINE_CREATED));
                    });
        }

        Set<String> forced = new LinkedHashSet<>();
        Path sources = locateSources();
        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> forced.addAll(matches(read(path), FORCED)));
        }

        assertTrue(!forced.isEmpty(), "没有扫描到 FORCE INDEX，路径可能不对：" + sources);
        List<String> missing = forced.stream().filter(name -> !created.contains(name)).toList();
        assertTrue(missing.isEmpty(),
                "代码强制引用但迁移未创建的索引：" + missing + "（迁移目录：" + migrations + "）");
    }

    private static Set<String> matches(String text, Pattern pattern) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1).toLowerCase());
        }
        return names;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new IllegalStateException("读取失败：" + path, exc);
        }
    }

    private static Path locateMigrations() {
        for (Path candidate : List.of(Path.of("..", "db", "migration"), Path.of("db", "migration"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 db/migration（当前目录：" + Path.of(".").toAbsolutePath() + "）");
    }

    private static Path locateSources() {
        for (Path candidate : List.of(Path.of("src", "main", "java"), Path.of("backend", "src", "main", "java"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 backend/src/main/java（当前目录：" + Path.of(".").toAbsolutePath() + "）");
    }
}
