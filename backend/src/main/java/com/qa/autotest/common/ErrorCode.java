package com.qa.autotest.common;

/**
 * 错误码段位分配（v0.1 冻结，后续只增不改）。
 *
 * <pre>
 * 0xxxx  成功
 * 4xxxx  客户端错误（参数 / 认证 / 权限 / 不存在）
 * 5xxxx  服务端错误（内部异常 / 依赖不可用）
 * </pre>
 *
 * <p>每个错误码都带一个稳定的英文 {@code name}，对外文档与日志引用 name，不引用中文文案。
 */
public enum ErrorCode {

    /** 成功。 */
    SUCCESS(0, "OK"),

    /** 参数校验失败。 */
    INVALID_ARGUMENT(40001, "INVALID_ARGUMENT"),

    /** 未认证。 */
    UNAUTHENTICATED(40100, "UNAUTHENTICATED"),

    /** 无权限。 */
    FORBIDDEN(40300, "FORBIDDEN"),

    /** 安全级拦截：L2 高危 / LX 未判定用例禁止自动执行（ADR-0013，G2-3）。 */
    SAFETY_LEVEL_BLOCKED(40301, "SAFETY_LEVEL_BLOCKED"),

    /** 资源不存在。 */
    NOT_FOUND(40400, "NOT_FOUND"),

    /** 请求方法不被支持（如对只读审计接口发起 DELETE）。 */
    METHOD_NOT_ALLOWED(40500, "METHOD_NOT_ALLOWED"),

    /** 冲突（唯一键、状态机非法跃迁）。 */
    CONFLICT(40900, "CONFLICT"),

    /** 项目必须保留至少一名项目经理（v4.4.0 / FR-v4.4-05）。 */
    LAST_PM_REQUIRED(40901, "LAST_PM_REQUIRED"),

    /** 编码已使用过唯一一次变更机会（v4.4.0 / FR-v4.4-08）。 */
    CODE_ALREADY_CHANGED(40902, "CODE_ALREADY_CHANGED"),

    /** 触发限流（令牌级滑动窗口超限，PLN-03）。 */
    TOO_MANY_REQUESTS(42900, "TOO_MANY_REQUESTS"),

    /** 服务内部错误。 */
    INTERNAL_ERROR(50000, "INTERNAL_ERROR"),

    /** 依赖不可用（如 PDF 打印所需的浏览器缺失 / 打印失败）。 */
    DEPENDENCY_UNAVAILABLE(50300, "DEPENDENCY_UNAVAILABLE");

    private final int code;
    private final String name;

    ErrorCode(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int code() {
        return code;
    }

    public String codeName() {
        return name;
    }
}
