package com.qa.autotest.infrastructure;

/**
 * 资产可见口径的唯一来源（AST-05 / DEV-PLAN §10.8 步骤 3）。
 *
 * <p>口径：**本项目私有资产** 或 **平台共享且本项目有引用**。两个条件之外不允许任何变体，
 * 所以这里给出的是可直接拼接的 SQL 片段，而不是各仓储各写一遍 {@code project_id = ?}。
 *
 * <p>拼接顺序固定为 {@code SELECT ... FROM 主表 别名 + XXX_JOIN + XXX_WHERE}；
 * 占位符顺序也固定：**先 JOIN 里的 project_id，再 WHERE 里的 project_id**，
 * 之后才追加业务过滤条件。调用方按这个顺序传两个 projectId 即可。
 *
 * <p>别名约定：设备 {@code d}、链路 {@code l}、凭据 {@code c}、引用 {@code pa}/{@code lpa}/{@code ca}。
 * 链路仓储里 {@code pa} 已被 A 端端口占用，因此链路专用 {@code lpa}。
 */
public final class AssetScopeSql {

    private AssetScopeSql() {
    }

    /** 设备：只需拼在 {@code FROM device d} 之后。 */
    public static final String DEVICE_JOIN = """
            LEFT JOIN project_asset pa
                   ON pa.asset_id = d.id AND pa.asset_type = 'DEVICE' AND pa.project_id = ?
            """;
    /** 设备可见性公式的唯一真值：项目态与平台态两个 WHERE 都从这里拼，不复制第二份。 */
    private static final String DEVICE_BODY =
            "d.project_id = ? OR (d.owner_scope = 'PLATFORM' AND pa.id IS NOT NULL)";
    public static final String DEVICE_WHERE = " WHERE (" + DEVICE_BODY + ")";

    /**
     * 平台项目自身：公共资产恒可见（v5.13.0 W7 实测修复）。
     *
     * <p>全局资产列表走 {@code owner_scope='PLATFORM'}，从不要求 project_asset 引用；
     * 画像若只按「本项目已引用」筛，平台项目会因为自己没有 DEVICE 引用而返回空列表
     * （W7 真实 HTTP 探针：count=0）。这里补的是同一个口径，不是放宽租户边界：
     * 非平台项目仍走 {@link #DEVICE_WHERE}。
     */
    public static final String DEVICE_WHERE_PLATFORM =
            " WHERE (" + DEVICE_BODY + " OR d.owner_scope = 'PLATFORM')";

    /** 链路：主表别名 {@code l}。 */
    public static final String LINK_JOIN = """
            LEFT JOIN project_asset lpa
                   ON lpa.asset_id = l.id AND lpa.asset_type = 'LINK' AND lpa.project_id = ?
            """;
    public static final String LINK_WHERE =
            " WHERE (l.project_id = ? OR (l.owner_scope = 'PLATFORM' AND lpa.id IS NOT NULL))";

    /** 凭据：主表别名 {@code c}。 */
    public static final String CREDENTIAL_JOIN = """
            LEFT JOIN project_asset ca
                   ON ca.asset_id = c.id AND ca.asset_type = 'CREDENTIAL' AND ca.project_id = ?
            """;
    public static final String CREDENTIAL_WHERE =
            " WHERE (c.project_id = ? OR (c.owner_scope = 'PLATFORM' AND ca.id IS NOT NULL))";

    /** 资产类型 → 表名；不认识的类型一律拒绝，避免把类型直接拼进 SQL。 */
    public static String tableOf(String assetType) {
        return switch (normalize(assetType)) {
            case "DEVICE" -> "device";
            case "LINK" -> "link";
            case "CREDENTIAL" -> "credential";
            default -> throw new IllegalArgumentException("不支持的资产类型：" + assetType);
        };
    }

    /** 统一大写，接口里传 {@code device} 也认。 */
    public static String normalize(String assetType) {
        if (assetType == null || assetType.isBlank()) {
            throw new IllegalArgumentException("资产类型不能为空");
        }
        return assetType.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
