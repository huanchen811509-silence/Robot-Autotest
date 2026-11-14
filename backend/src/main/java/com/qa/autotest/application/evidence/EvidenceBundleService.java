package com.qa.autotest.application.evidence;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 证据包写入 / 读取 / 校验（v5.7.0 / W57-24、25）。
 *
 * <p>写链路：文件名 = 内容 sha256；先写<b>同挂载点</b>临时目录，再
 * {@code Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)} 原子发布对象；全部对象就位后
 * 原子替换 pointer 文件；最后把 {@code evidence_bundle} 行置为 ready。任何一步失败置 failed。
 *
 * <p>读链路：重算每个对象文件的 sha256 与文件名比对，不符即抛异常；指针文件也重算 sha256
 * 与 DB 的 pointer_sha 对账。旧扁平目录 {@code <root>/<sha>} 读兼容。
 *
 * <p>同内容去重靠「目标对象已存在就跳过发布」：并发写同一内容时最后一个 ATOMIC_MOVE 覆盖，
 * 内容一致，无害。不引任何第三方库，只用 {@code java.nio.file} + {@code MessageDigest}。
 */
@Service
public class EvidenceBundleService {

    /** 单文件上限 512MB（超出直接拒绝，不写临时文件）。 */
    public static final long MAX_FILE_BYTES = 512L * 1024 * 1024;

    private final EvidencePaths paths;
    private final EvidenceBundleStore repository;
    private final com.qa.autotest.application.iam.AuditService audit;

    public EvidenceBundleService(EvidencePaths paths, EvidenceBundleStore repository,
                                 com.qa.autotest.application.iam.AuditService audit) {
        this.paths = paths;
        this.repository = repository;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- write

    /** 把若干文件写成一批证据；返回指针摘要。同 bundleKey 重复写会原子替换旧指针。 */
    public BundleDescriptor write(String bundleKey, List<Path> files) {
        String key = requireKey(bundleKey);
        List<Path> sources = files == null ? List.of() : files;
        try {
            paths.ensureDirs();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "证据包目录不可用");
        }

        List<Staged> staged = new ArrayList<>();
        try {
            for (Path source : sources) {
                if (source == null || !Files.isRegularFile(source)) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "证据文件不存在");
                }
                long size = Files.size(source);
                if (size > MAX_FILE_BYTES) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "单文件超过 512MB 上限");
                }
                staged.add(new Staged(source, EvidencePaths.sha256Of(source), size,
                        safeName(source)));
            }
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "证据文件读取失败");
        }

        repository.begin(key, TenantContext.projectId());
        try {
            long totalBytes = 0L;
            for (Staged item : staged) {
                Path target = paths.objectFile(item.sha256());
                if (!Files.exists(target)) {
                    Path temp = paths.objectTemp(UUID.randomUUID() + ".part");
                    Files.createDirectories(target.getParent());
                    Files.copy(item.sourcePath(), temp, StandardCopyOption.REPLACE_EXISTING);
                    publish(temp, target);
                }
                totalBytes += item.size();
            }
            byte[] pointer = pointerBytes(staged);
            String pointerSha = EvidencePaths.sha256Hex(pointer);
            Path pointerTemp = paths.objectTemp(UUID.randomUUID() + ".ptr");
            Files.write(pointerTemp, pointer);
            Path pointerTarget = paths.pointerFile(key);
            Files.createDirectories(pointerTarget.getParent());
            publish(pointerTemp, pointerTarget);

            repository.markReady(key, pointerSha, staged.size(), totalBytes);
            return new BundleDescriptor(key, pointerSha, staged.size(), totalBytes, "ready");
        } catch (BusinessException ex) {
            safeMarkFailed(key);
            throw ex;
        } catch (IOException | RuntimeException ex) {
            safeMarkFailed(key);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "证据包写入失败");
        }
    }

    // ---------------------------------------------------------------- read / verify

    /** 读取整批证据；读时重算 sha256，篡改 / 缺失一律抛 BusinessException。 */
    public List<EvidenceEntry> read(String bundleKey) {
        String key = requireKey(bundleKey);
        try {
            Path pointer = locatePointer(key);
            if (pointer == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "证据包不存在: " + key);
            }
            byte[] pointerBytes = Files.readAllBytes(pointer);
            checkPointerSha(key, pointerBytes);
            List<EvidenceEntry> entries = new ArrayList<>();
            for (String line : new String(pointerBytes, StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    entries.add(readEntry(line));
                }
            }
            return entries;
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "证据包读取失败");
        }
    }

    /** 只读校验：不返回文件内容，给出通过 / 问题清单。 */
    public VerifyResult verify(String bundleKey) {
        try {
            int checked = read(bundleKey).size();
            return new VerifyResult(true, checked, List.of());
        } catch (BusinessException ex) {
            return new VerifyResult(false, 0, List.of(ex.getMessage()));
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 原子发布：优先 ATOMIC_MOVE，文件系统不支持时降级普通 REPLACE_EXISTING。 */
    private void publish(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileAlreadyExistsException ex) {
            // 部分实现（如 Windows）在 ATOMIC_MOVE 下不替换已存在目标：先删再普通 move 兜底。
            Files.deleteIfExists(target);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void checkPointerSha(String key, byte[] pointerBytes) {
        String actual = sha256Hex(pointerBytes);
        repository.find(key).ifPresent(row -> {
            if ("ready".equals(row.status()) && !actual.equals(row.pointerSha())) {
                audit.record("evidence.hash-mismatch", "evidence_pointer", key,
                        "expected=" + row.pointerSha() + ", actual=" + actual);
                throw new BusinessException(ErrorCode.CONFLICT, "证据包指针 sha256 校验失败");
            }
        });
    }

    private EvidenceEntry readEntry(String line) throws IOException {
        String[] parts = line.split("\t", 3);
        if (parts.length < 3) {
            throw new BusinessException(ErrorCode.CONFLICT, "证据包指针格式非法");
        }
        String sha = parts[0];
        long size;
        try {
            size = Long.parseLong(parts[1]);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "证据包指针格式非法");
        }
        Path object = locateObject(sha);
        if (object == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "证据文件缺失: " + parts[2]);
        }
        byte[] content = Files.readAllBytes(object);
        // 读时重算：文件名就是内容 sha256，对不上说明被篡改或写坏了。
        if (!sha256Hex(content).equals(sha) || content.length != size) {
            // 复核 P1-8：哈希不符必须留审计痕迹——不然「证据被改过」这件事只能靠人复述。
            audit.record("evidence.hash-mismatch", "evidence_object", parts[2],
                    "expected=" + sha + ", actual=" + sha256Hex(content)
                            + ", expectedSize=" + size + ", actualSize=" + content.length);
            throw new BusinessException(ErrorCode.CONFLICT, "证据文件 sha256 校验失败: " + parts[2]);
        }
        return new EvidenceEntry(parts[2], sha, size, content);
    }

    private Path locateObject(String sha) {
        Path sharded = paths.objectFile(sha);
        if (Files.isRegularFile(sharded)) {
            return sharded;
        }
        Path legacy = paths.legacyFlat(sha);
        return Files.isRegularFile(legacy) ? legacy : null;
    }

    private Path locatePointer(String key) {
        Path pointer = paths.pointerFile(key);
        if (Files.isRegularFile(pointer)) {
            return pointer;
        }
        Path legacy = paths.legacyPointer(key);
        return Files.isRegularFile(legacy) ? legacy : null;
    }

    private byte[] pointerBytes(List<Staged> staged) {
        StringBuilder text = new StringBuilder();
        for (Staged item : staged) {
            text.append(item.sha256()).append('\t').append(item.size()).append('\t')
                    .append(item.name()).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String safeName(Path source) {
        String name = source.getFileName() == null ? "artifact" : source.getFileName().toString();
        String cleaned = name.replaceAll("[\\r\\n\\t]", "_");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    private void safeMarkFailed(String key) {
        try {
            repository.markFailed(key);
        } catch (RuntimeException ignored) {
            // DB 不可用时也不能用「标记失败」掩盖原始写入失败
        }
    }

    private static String requireKey(String bundleKey) {
        if (bundleKey == null || bundleKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "bundleKey 不能为空");
        }
        return bundleKey.trim();
    }

    /** Service 自己持有 MessageDigest：读时重算不走缓存、不信任任何落库摘要。 */
    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 暂存条目：源文件路径 + 内容 sha + 大小 + 展示名。 */
    private record Staged(Path sourcePath, String sha256, long size, String name) {
    }

    public record BundleDescriptor(String bundleKey, String pointerSha, int fileCount,
                                   long totalBytes, String status) {
    }

    public record EvidenceEntry(String name, String sha256, long size, byte[] content) {
    }

    public record VerifyResult(boolean ok, int checked, List<String> problems) {
    }
}
