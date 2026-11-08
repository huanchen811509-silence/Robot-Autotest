package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.repository.ExecutionSelectionRepository;
import com.qa.autotest.domain.caseasset.FrozenSelection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 执行选择冻结（SCEN-02）：把来源设计的执行选择固化成不可变快照。
 * 冻结只写 execution_selection 一行，不触碰来源 test_set；同一设计两次冻结因
 * Instant 与 UUID nonce 参与 SHA 输入，必然得到不同 sha256。
 */
@Service
public class ExecutionSelectionService {

    private final ExecutionSelectionRepository repository;

    public ExecutionSelectionService(ExecutionSelectionRepository repository) {
        this.repository = repository;
    }

    /** 冻结：生成新 sha256，只发 INSERT，返回不可变 record。 */
    public FrozenSelection freeze(long projectId, Long designId,
                                  String payloadJson, String resourceSnapshotJson) {
        Instant now = Instant.now();
        String sha = computeSha256(projectId, designId, payloadJson, resourceSnapshotJson,
                now, UUID.randomUUID());
        FrozenSelection draft = new FrozenSelection(
                null, projectId, designId, payloadJson, resourceSnapshotJson, sha, now);
        long id = repository.insert(draft);
        return new FrozenSelection(
                id, projectId, designId, payloadJson, resourceSnapshotJson, sha, now);
    }

    /** 只读查询。 */
    public Optional<FrozenSelection> find(long projectId, long id) {
        return repository.find(projectId, id);
    }

    /** SHA-256 输入含 Instant 与 UUID nonce，保证同一内容两次生成结果不同。 */
    public static String computeSha256(long projectId, Long designId,
                                       String payloadJson, String resourceSnapshotJson,
                                       Instant instant, UUID nonce) {
        String material = projectId + "|" + designId + "|" + payloadJson + "|"
                + resourceSnapshotJson + "|" + instant + "|" + nonce;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
