package com.qa.autotest.application.asset;

/**
 * 采集画像（v5.13.0 / FR-v5.13-01）：查询期聚合，不新增业务表。
 *
 * <p>一张「设备现在能不能采、为什么」的只读视图：绑定列来自 V11_45、
 * 最近验证来自只增不改的 {@code credential_verification}、最近采集来自
 * append-only 的 {@code device_host_spec}。三处事实在查询时拼成一行，
 * 不物化、不缓存，也就不会出现「采集完列表还是旧状态」。
 *
 * <p>状态判定刻意做成静态纯函数 {@link #deriveState}，单测不需要 DB 就能把
 * READY / PARTIAL / BLOCKED 与三种 BLOCKED 原因逐条钉住。
 *
 * <p>零泄漏：本 record 只带凭证<b>名字的掩码</b>，不带 secret_ref / 口令 / 私钥。
 */
public record DeviceCollectProfile(
        long deviceId,
        Long projectId,
        String ownerScope,
        String name,
        String managementIp,
        Long managementCredentialId,
        Long bmcCredentialId,
        String boundCredentialName,
        String boundCredentialMasked,
        Long lastVerifiedCredentialId,
        String lastVerifiedAt,
        String verifyResult,
        String verifyFailureKind,
        String lastCollectAt,
        String lastCollectStatus,
        int collectCount,
        boolean usable,
        String profileState,
        String blockedReason) {

    public static final String READY = "READY";
    public static final String PARTIAL = "PARTIAL";
    public static final String BLOCKED = "BLOCKED";

    public static final String NO_MANAGEMENT_IP = "NO_MANAGEMENT_IP";
    public static final String NO_CREDENTIAL = "NO_CREDENTIAL";
    public static final String CREDENTIAL_REVOKED = "CREDENTIAL_REVOKED";

    /** 既有 V11_64 枚举是 SUCCESS/FAILED；改名会打断 options 的「已验证优先」排序。 */
    public static final String VERIFY_SUCCESS = "SUCCESS";

    /** 聚合 SQL 的一行原始值；状态派生全在 of() 里，SQL 不写 CASE WHEN。 */
    public record RawRow(
            long deviceId,
            Long projectId,
            String ownerScope,
            String name,
            String managementIp,
            Long mgmtCredId,
            Long bmcCredId,
            String mgmtCredName,
            String bmcCredName,
            String mgmtCredStatus,
            String bmcCredStatus,
            Long lastVerifiedCredentialId,
            String lastVerifiedAt,
            String verifyResult,
            String verifyFailureKind,
            String lastCollectAt,
            String lastCollectStatus,
            int collectCount) {
    }

    public record State(String state, String reason, boolean usable) {
    }

    /**
     * 三态 + 三种 BLOCKED 原因。判据顺序不可调换：无管理 IP 的设备即使没绑凭证，
     * 原因也必须是 NO_MANAGEMENT_IP（先补 IP 才谈得上绑凭证），否则用户会被指错方向。
     */
    public static State deriveState(String managementIp, Long mgmtCredId, Long bmcCredId,
                                    boolean credentialRevoked, String verifyResult) {
        if (managementIp == null || managementIp.isBlank()) {
            return new State(BLOCKED, NO_MANAGEMENT_IP, false);
        }
        if (mgmtCredId == null && bmcCredId == null) {
            return new State(BLOCKED, NO_CREDENTIAL, false);
        }
        if (credentialRevoked) {
            return new State(BLOCKED, CREDENTIAL_REVOKED, false);
        }
        if (VERIFY_SUCCESS.equalsIgnoreCase(verifyResult == null ? "" : verifyResult)) {
            return new State(READY, null, true);
        }
        return new State(PARTIAL, null, true);
    }

    /** 只留名字前两位，绝不可回传 secret_ref。 */
    public static String maskOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String value = name.trim();
        return value.substring(0, Math.min(2, value.length())) + "***";
    }

    public static DeviceCollectProfile of(RawRow row) {
        String boundName = row.mgmtCredName() != null ? row.mgmtCredName() : row.bmcCredName();
        String activeStatus = row.mgmtCredId() != null
                ? row.mgmtCredStatus() : row.bmcCredStatus();
        boolean revoked = "deprecated".equalsIgnoreCase(activeStatus == null ? "" : activeStatus);
        State state = deriveState(row.managementIp(), row.mgmtCredId(), row.bmcCredId(),
                revoked, row.verifyResult());
        return new DeviceCollectProfile(row.deviceId(), row.projectId(), row.ownerScope(),
                row.name(), row.managementIp(), row.mgmtCredId(), row.bmcCredId(),
                boundName, maskOf(boundName), row.lastVerifiedCredentialId(),
                row.lastVerifiedAt(), row.verifyResult(), row.verifyFailureKind(),
                row.lastCollectAt(), row.lastCollectStatus(), row.collectCount(),
                state.usable(), state.state(), state.reason());
    }
}
