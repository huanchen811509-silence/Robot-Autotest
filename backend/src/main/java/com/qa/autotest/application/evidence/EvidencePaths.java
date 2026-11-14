package com.qa.autotest.application.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 证据包磁盘布局（v5.7.0 / W57-24、25）。
 *
 * <p>内容寻址：对象文件名 = 内容 sha256，放在 {@code ab/cd/<sha>} 两级分片目录里；
 * 指针文件按 bundleKey 的 sha256 命名，放在 {@code pointers/} 下；临时目录 {@code .tmp/}
 * 与对象目录<b>同在 root 挂载点下</b>，这是 {@code Files.move(..., ATOMIC_MOVE)} 能成立的前提
 * （跨文件系统 rename 会退化成 copy+delete，就不再原子）。
 *
 * <p>{@link #legacyFlat} 保留旧扁平目录 {@code <root>/<sha>} 的读兼容；写入一律走分片。
 * 指针文件名用 bundleKey 的 sha256，不直接拿业务键拼路径，杜绝 {@code ../} 穿越。
 */
@Component
public final class EvidencePaths {

    private final Path root;

    /** 生产装配：evidence.root 缺省落到工作区下的 evidence/。单一公开构造器。 */
    public EvidencePaths(@Value("${evidence.root:./workspace/evidence}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /** 单测 / 嵌入式装配（{@code @TempDir}）：复用同一构造器，不是第二个公开构造器。 */
    public static EvidencePaths of(Path root) {
        return new EvidencePaths(root.toString());
    }

    public Path root() {
        return root;
    }

    /** 临时目录与最终目录同一挂载点，保证 ATOMIC_MOVE 可用。 */
    public Path tempDir() {
        return root.resolve(".tmp");
    }

    public Path pointerDir() {
        return root.resolve("pointers");
    }

    /** {@code ab/cd/<sha>}：取 sha 前 4 个十六进制字符拆两级目录。 */
    public Path objectFile(String sha) {
        String hex = requireSha(sha);
        return root.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex);
    }

    /** 旧版扁平布局 {@code <root>/<sha>}，仅读兼容。 */
    public Path legacyFlat(String sha) {
        return root.resolve(requireSha(sha));
    }

    public Path pointerFile(String bundleKey) {
        return pointerDir().resolve(
                sha256Hex(bundleKey.getBytes(StandardCharsets.UTF_8)) + ".ptr");
    }

    /** 旧版指针（直接放 root 下）读兼容。 */
    public Path legacyPointer(String bundleKey) {
        return root.resolve(sha256Hex(bundleKey.getBytes(StandardCharsets.UTF_8)) + ".ptr");
    }

    public Path objectTemp(String name) {
        return tempDir().resolve(name);
    }

    public void ensureDirs() throws IOException {
        Files.createDirectories(root());
        Files.createDirectories(tempDir());
        Files.createDirectories(pointerDir());
    }

    private static String requireSha(String sha) {
        if (sha == null || sha.length() != 64) {
            throw new IllegalArgumentException("sha256 必须是 64 位十六进制字符串");
        }
        return sha.toLowerCase(Locale.ROOT);
    }

    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    /** 流式算文件 sha256：>512MB 的文件也不会被整体读进堆。 */
    static String sha256Of(Path path) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
