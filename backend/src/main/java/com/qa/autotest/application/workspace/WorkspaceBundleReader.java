package com.qa.autotest.application.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.workspace.ManifestFile;
import com.qa.autotest.domain.workspace.WorkspaceManifest;
import com.qa.autotest.domain.workspace.WorkspaceSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * 读 + 验一个工作区包（contract §3/§8）：格式版本 → 路径安全 → 双向清单一致 → 逐文件
 * sha256/字节数 → 服务端 HMAC → 配额。任何一步失败整包拒绝，不做部分导入。
 */
@Component
public class WorkspaceBundleReader {

    private final WorkspaceProperties properties;
    private final WorkspaceBundleSigner signer;
    private final ObjectMapper mapper;

    public WorkspaceBundleReader(WorkspaceProperties properties, WorkspaceBundleSigner signer,
                                 ObjectMapper mapper) {
        this.properties = properties;
        this.signer = signer;
        this.mapper = mapper;
    }

    public record VerifiedBundle(
            Path root,
            WorkspaceManifest manifest,
            WorkspaceSnapshot.ProjectInfo project,
            List<WorkspaceSnapshot.MemberInfo> members,
            List<WorkspaceSnapshot.CaseInfo> cases,
            List<WorkspaceSnapshot.CaseSetInfo> caseSets,
            List<WorkspaceSnapshot.AssetInfo> assets,
            List<WorkspaceSnapshot.CredentialInfo> credentials,
            List<WorkspaceSnapshot.EnvironmentInfo> environments,
            List<WorkspaceSnapshot.ReportInfo> reports,
            List<WorkspaceSnapshot.RunInfo> runs,
            List<WorkspaceSnapshot.FileInfo> files,
            String manifestSha256,
            long totalBytes) {
    }

    public VerifiedBundle verify(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw invalid("工作区包不存在: " + dir);
        }
        for (String sub : WorkspaceBundleWriter.REQUIRED_DIRS) {
            if (!Files.isDirectory(dir.resolve(sub))) {
                throw invalid("包缺少目录: " + sub);
            }
        }
        for (String top : List.of("project.json", "manifest.json", "members.json", "README.txt")) {
            if (!Files.isRegularFile(dir.resolve(top))) {
                throw invalid("包缺少文件: " + top);
            }
        }
        WorkspaceManifest manifest;
        JsonNode projectNode;
        try {
            JsonNode manifestNode = mapper.readTree(dir.resolve("manifest.json").toFile());
            manifest = mapper.treeToValue(manifestNode, WorkspaceManifest.class);
            projectNode = mapper.readTree(dir.resolve("project.json").toFile());
        } catch (IOException | RuntimeException exc) {
            throw invalid("包内 JSON 无法解析: " + exc.getMessage());
        }
        if (manifest.formatVersion() != 1) {
            throw invalid("不支持的包格式版本 " + manifest.formatVersion() + "（本版本只接受 1）");
        }
        if (projectNode.path("formatVersion").asInt(0) != 1) {
            throw invalid("project.json formatVersion 必须为 1");
        }
        String code = projectNode.path("code").asText("");
        if (code.isBlank()) {
            throw invalid("project.json 缺少项目 code");
        }
        if (manifest.projectCode() != null && !manifest.projectCode().isBlank()
                && !manifest.projectCode().equalsIgnoreCase(code)) {
            throw invalid("manifest.projectCode 与 project.json code 不一致");
        }

        ObjectNode body = mapper.createObjectNode();
        body.setAll((ObjectNode) manifestNode(dir));
        JsonNode signature = body.remove("signature");
        String sigValue = signature == null ? "" : signature.path("value").asText("");
        if (signature == null || !WorkspaceBundleSigner.ALGORITHM.equals(signature.path("algorithm").asText(""))) {
            throw invalid("manifest 缺少合法签名（algorithm 必须为 " + WorkspaceBundleSigner.ALGORITHM + "）");
        }
        if (!signer.verify(body, sigValue)) {
            throw invalid("manifest 验签失败（包被篡改或密钥不匹配）");
        }

        List<ManifestFile> listed = manifest.files() == null ? List.of() : manifest.files();
        Set<String> declared = new HashSet<>();
        for (ManifestFile f : listed) {
            String safe = WorkspaceBundleWriter.safeRelative(f.path());
            if (!declared.add(safe)) {
                throw invalid("manifest 重复条目: " + safe);
            }
            Path file = dir.resolve(safe);
            if (!Files.isRegularFile(file)) {
                throw invalid("清单有、目录无: " + safe);
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException exc) {
                throw invalid("读取失败: " + safe);
            }
            if (!WorkspaceBundleWriter.sha256Hex(bytes).equalsIgnoreCase(f.sha256())) {
                throw invalid("sha256 不一致: " + safe);
            }
            if (bytes.length != f.bytes()) {
                throw invalid("字节数不一致: " + safe);
            }
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            List<String> extras = new ArrayList<>();
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (!"manifest.json".equals(rel) && !declared.contains(rel)) {
                    extras.add(rel);
                }
            });
            if (!extras.isEmpty()) {
                throw invalid("目录有、清单无: " + extras);
            }
        } catch (IOException exc) {
            throw invalid("遍历包目录失败: " + exc.getMessage());
        }

        long totalBytes = listed.stream().mapToLong(ManifestFile::bytes).sum();
        if (listed.size() > properties.getMaxFiles() || totalBytes > properties.getMaxBytes()) {
            throw invalid("包超过配额（文件数 " + listed.size() + "/" + properties.getMaxFiles()
                    + "，字节 " + totalBytes + "/" + properties.getMaxBytes() + "）");
        }

        WorkspaceSnapshot.ProjectInfo project = new WorkspaceSnapshot.ProjectInfo(
                code,
                projectNode.path("name").asText(""),
                projectNode.path("description").asText(""),
                projectNode.path("testKernel").asText(""));

        List<WorkspaceSnapshot.MemberInfo> members =
                readList(dir, "members.json", "members", WorkspaceSnapshot.MemberInfo.class);
        List<WorkspaceSnapshot.CaseInfo> cases =
                readList(dir, "cases/cases.json", "cases", WorkspaceSnapshot.CaseInfo.class);
        List<WorkspaceSnapshot.CaseSetInfo> caseSets =
                readList(dir, "case-sets/case-sets.json", "sets", WorkspaceSnapshot.CaseSetInfo.class);
        List<WorkspaceSnapshot.AssetInfo> assets =
                readList(dir, "assets/assets.json", "assets", WorkspaceSnapshot.AssetInfo.class);
        List<WorkspaceSnapshot.CredentialInfo> credentials =
                readList(dir, "assets/credentials.json", "credentials", WorkspaceSnapshot.CredentialInfo.class);
        List<WorkspaceSnapshot.EnvironmentInfo> environments =
                readList(dir, "environments/environments.json", "environments", WorkspaceSnapshot.EnvironmentInfo.class);
        List<WorkspaceSnapshot.ReportInfo> reports =
                readList(dir, "reports/reports.json", "reports", WorkspaceSnapshot.ReportInfo.class);
        List<WorkspaceSnapshot.RunInfo> runs =
                readList(dir, "runs/runs.json", "runs", WorkspaceSnapshot.RunInfo.class);

        List<WorkspaceSnapshot.FileInfo> files = new ArrayList<>();
        for (ManifestFile f : listed) {
            String rel = f.path().replace('\\', '/');
            if (!rel.startsWith("files/")) {
                continue;
            }
            String projectPath = rel.substring("files/".length());
            try {
                String content = Files.readString(dir.resolve(rel), StandardCharsets.UTF_8);
                files.add(new WorkspaceSnapshot.FileInfo(projectPath, content));
            } catch (IOException exc) {
                throw invalid("读取源文件失败: " + rel);
            }
        }
        String manifestSha = WorkspaceBundleWriter.sha256Hex(bytesOf(dir.resolve("manifest.json")));
        return new VerifiedBundle(dir, manifest, project, members, cases, caseSets, assets, credentials,
                environments, reports, runs, files, manifestSha, totalBytes);
    }

    private JsonNode manifestNode(Path dir) {
        try {
            return mapper.readTree(dir.resolve("manifest.json").toFile());
        } catch (IOException exc) {
            throw invalid("manifest.json 无法解析: " + exc.getMessage());
        }
    }

    private <T> List<T> readList(Path dir, String file, String key, Class<T> type) {
        try {
            JsonNode node = mapper.readTree(dir.resolve(file).toFile());
            JsonNode array = node.path(key);
            if (!array.isArray()) {
                throw invalid(file + " 缺少数组字段 " + key);
            }
            return mapper.readerForListOf(type).readValue(array);
        } catch (IOException exc) {
            throw invalid(file + " 解析失败: " + exc.getMessage());
        }
    }

    private static byte[] bytesOf(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exc) {
            throw invalid("读取失败: " + file.getFileName());
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
