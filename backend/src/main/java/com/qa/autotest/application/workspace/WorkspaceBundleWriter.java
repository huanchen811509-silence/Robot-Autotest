package com.qa.autotest.application.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.workspace.ManifestFile;
import com.qa.autotest.domain.workspace.WorkspaceSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把 {@link WorkspaceSnapshot} 落成契约目录（contract §1/§3/§7）：
 * 先写临时目录，再原子替换正式目录，避免「导出一半留下脏包」。
 */
@Component
public class WorkspaceBundleWriter {

    public static final List<String> REQUIRED_DIRS =
            List.of("cases", "case-sets", "assets", "environments", "reports", "runs", "files");
    public static final String README_TEXT =
            "本目录是交换格式，不是运行时数据源。\n"
            + "手工改动目录不会自动生效：需要改动时请走平台「工作区导入」显式导入。\n"
            + "manifest.json 由服务端签名，请勿手改；改文件后请重新导出。\n";

    private final WorkspaceProperties properties;
    private final WorkspaceBundleSigner signer;
    private final ObjectMapper mapper;

    public WorkspaceBundleWriter(WorkspaceProperties properties, WorkspaceBundleSigner signer,
                                 ObjectMapper mapper) {
        this.properties = properties;
        this.signer = signer;
        this.mapper = mapper;
    }

    public record Result(String bundlePath, String manifestSha256, int fileCount, long totalBytes) {
    }

    /**
     * @param targetDir  正式目录（必须是 workspace.root 下的项目 code 目录）
     * @param granularity PROJECT / DIR / FILE
     * @param rootPath    DIR / FILE 粒度的包内相对路径，可为 null
     */
    public Result write(Path targetDir, WorkspaceSnapshot snapshot, String granularity, String rootPath) {
        String level = normalizeLevel(granularity);
        String scope = normalizeScope(level, rootPath);
        String exportedAt = java.time.Instant.now().toString();
        Path temp = targetDir.resolveSibling("." + targetDir.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(temp);
            for (String dir : REQUIRED_DIRS) {
                Files.createDirectories(temp.resolve(dir));
            }
            Files.writeString(temp.resolve("README.txt"), README_TEXT, StandardCharsets.UTF_8);
            writeJson(temp.resolve("project.json"), projectJson(snapshot, level, scope, exportedAt));
            writeJson(temp.resolve("members.json"), mapper.createObjectNode().set(
                    "members", mapper.valueToTree(scope.isEmpty() ? snapshot.members() : List.of())));
            writeJson(temp.resolve("cases/cases.json"), mapper.createObjectNode().set(
                    "cases", mapper.valueToTree(includeDomain(scope, "cases") ? snapshot.cases() : List.of())));
            writeJson(temp.resolve("case-sets/case-sets.json"), mapper.createObjectNode().set(
                    "sets", mapper.valueToTree(includeDomain(scope, "case-sets") ? snapshot.caseSets() : List.of())));
            writeJson(temp.resolve("assets/assets.json"), mapper.createObjectNode().set(
                    "assets", mapper.valueToTree(includeDomain(scope, "assets") ? snapshot.assets() : List.of())));
            writeJson(temp.resolve("assets/credentials.json"), mapper.createObjectNode().set(
                    "credentials", mapper.valueToTree(includeDomain(scope, "assets") ? snapshot.credentials() : List.of())));
            writeJson(temp.resolve("environments/environments.json"), mapper.createObjectNode().set(
                    "environments", mapper.valueToTree(
                            includeDomain(scope, "environments") ? snapshot.environments() : List.of())));
            writeJson(temp.resolve("reports/reports.json"), mapper.createObjectNode().set(
                    "reports", mapper.valueToTree(includeDomain(scope, "reports") ? snapshot.reports() : List.of())));
            writeJson(temp.resolve("runs/runs.json"), mapper.createObjectNode().set(
                    "runs", mapper.valueToTree(includeDomain(scope, "runs") ? snapshot.runs() : List.of())));
            for (WorkspaceSnapshot.FileInfo file : snapshot.files()) {
                if (!includeFile(scope, file.path())) {
                    continue;
                }
                String safe = safeRelative(file.path());
                Path out = temp.resolve("files").resolve(safe).normalize();
                if (!out.startsWith(temp.resolve("files"))) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "文件路径越界: " + file.path());
                }
                byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > properties.getMaxFileBytes()) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                            "单文件超过配额 " + properties.getMaxFileBytes() + " 字节: " + file.path());
                }
                Files.createDirectories(out.getParent());
                Files.write(out, bytes);
            }
            JsonNode manifest = buildManifest(temp, snapshot, level, scope, exportedAt);
            byte[] manifestBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
            Files.write(temp.resolve("manifest.json"), manifestBytes);

            long total = 0;
            int count = 0;
            for (ManifestFile f : manifestFiles(temp)) {
                total += f.bytes();
                count++;
            }
            if (count > properties.getMaxFiles() || total > properties.getMaxBytes()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "导出超过配额（文件数 " + count + "/" + properties.getMaxFiles()
                        + "，字节 " + total + "/" + properties.getMaxBytes() + "）");
            }
            replace(targetDir, temp);
            return new Result(relativeToRoot(targetDir), sha256Hex(manifestBytes), count, total);
        } catch (IOException exc) {
            // 只读文件系统 / 目录权限不足要给出可行动的结论，而不是笼统的 50000。
            if (isReadOnly(exc)) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "工作区目录不可写（只读文件系统或权限不足）: " + exc.getMessage());
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "写工作区包失败: " + exc.getMessage());
        } finally {
            deleteQuietly(temp);
        }
    }

    private ObjectNode projectJson(WorkspaceSnapshot snapshot, String level, String scope, String exportedAt) {
        ObjectNode node = mapper.createObjectNode();
        WorkspaceSnapshot.ProjectInfo p = snapshot.project();
        node.put("formatVersion", 1);
        node.put("code", p.code());
        node.put("name", p.name());
        node.put("description", p.description() == null ? "" : p.description());
        node.put("testKernel", p.testKernel() == null ? "" : p.testKernel());
        node.put("exportedAt", exportedAt);
        node.put("generator", "robot-autotest/4.1.0");
        node.put("granularity", level.toLowerCase(java.util.Locale.ROOT));
        node.put("rootPath", scope.isEmpty() ? null : scope);
        return node;
    }

    private ObjectNode buildManifest(Path temp, WorkspaceSnapshot snapshot, String level, String scope,
                                     String exportedAt) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("formatVersion", 1);
        body.put("projectCode", snapshot.project().code());
        body.put("exportedAt", exportedAt);
        body.put("generator", "robot-autotest/4.1.0");
        body.put("granularity", level.toLowerCase(java.util.Locale.ROOT));
        if (scope.isEmpty()) {
            body.putNull("rootPath");
        } else {
            body.put("rootPath", scope);
        }
        ArrayNode files = mapper.createArrayNode();
        for (ManifestFile f : manifestFiles(temp)) {
            ObjectNode item = mapper.createObjectNode();
            item.put("path", f.path());
            item.put("sha256", f.sha256());
            item.put("bytes", f.bytes());
            files.add(item);
        }
        body.set("files", files);
        String signature = signer.sign(body);
        ObjectNode sig = mapper.createObjectNode();
        sig.put("algorithm", WorkspaceBundleSigner.ALGORITHM);
        sig.put("keyId", signer.keyId());
        sig.put("value", signature);
        body.set("signature", sig);
        return body;
    }

    private List<ManifestFile> manifestFiles(Path dir) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(dir.resolve("manifest.json")))
                    .forEach(paths::add);
        }
        paths.sort(Comparator.comparing(p -> dir.relativize(p).toString().replace('\\', '/')));
        List<ManifestFile> files = new ArrayList<>();
        for (Path p : paths) {
            byte[] bytes = Files.readAllBytes(p);
            files.add(new ManifestFile(
                    dir.relativize(p).toString().replace('\\', '/'), sha256Hex(bytes), bytes.length));
        }
        return files;
    }

    private static boolean includeDomain(String scope, String domain) {
        return scope.isEmpty() || scope.startsWith(domain + "/") || scope.equals(domain);
    }

    private static boolean includeFile(String scope, String path) {
        if (scope.isEmpty()) {
            return true;
        }
        String full = "files/" + path;
        return full.equals(scope) || full.startsWith(scope.endsWith("/") ? scope : scope + "/");
    }

    static String normalizeLevel(String granularity) {
        String level = granularity == null ? "PROJECT" : granularity.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("PROJECT", "DIR", "FILE").contains(level)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "粒度必须是 PROJECT / DIR / FILE");
        }
        return level;
    }

    static String normalizeScope(String level, String rootPath) {
        if ("PROJECT".equals(level)) {
            return "";
        }
        if (rootPath == null || rootPath.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, level + " 粒度必须指定 rootPath");
        }
        String scope = safeRelative(rootPath.trim().replaceAll("/+$", ""));
        if (!"FILE".equals(level) && scope.contains(".") && !scope.endsWith("/")) {
            // DIR 粒度也允许传目录名（无扩展名）；带扩展名说明调用方传了文件
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "DIR 粒度的 rootPath 必须是目录（如 files/）");
        }
        return scope;
    }

    /** 与 FileWorkspaceService.normalize 同口径：拒绝绝对路径、盘符、`..`、控制字符。 */
    public static String safeRelative(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "路径不能为空");
        }
        String value = raw.replace('\\', '/').trim();
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.startsWith("/") || (value.length() > 1 && value.charAt(1) == ':')) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "路径必须是相对路径: " + raw);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "路径含非法段: " + raw);
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < 32) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "路径含控制字符: " + raw);
                }
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private void replace(Path target, Path temp) throws IOException {
        Path trash = null;
        if (Files.exists(target)) {
            trash = target.resolveSibling("." + target.getFileName() + ".old-" + UUID.randomUUID());
            Files.move(target, trash, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exc) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (trash != null) {
                deleteQuietly(trash);
            }
        }
    }

    String relativeToRoot(Path path) {
        Path root = properties.rootPath();
        Path abs = path.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "目录不在工作区根下: " + path);
        }
        return root.relativize(abs).toString().replace('\\', '/');
    }

    private void writeJson(Path path, JsonNode node) throws IOException {
        Files.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exc) {
            throw new IllegalStateException(exc);
        }
    }

    /** 只读挂载（Errno 30）与目录权限不足都归为「环境不可写」，便于运维定位。 */
    private static boolean isReadOnly(IOException exc) {
        if (exc instanceof java.nio.file.AccessDeniedException) {
            return true;
        }
        String msg = exc.getMessage();
        return msg != null && (msg.contains("Read-only file system") || msg.contains("只读"));
    }

    static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响主流程；残留临时目录不会被当成正式包
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
