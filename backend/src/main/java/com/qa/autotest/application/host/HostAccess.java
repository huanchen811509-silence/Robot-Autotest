package com.qa.autotest.application.host;

/**
 * 远程主机访问材料（FR-v4.2-03）。
 *
 * <p>只在服务层校验通过后构造：项目成员/凭据权限（CREDENTIAL:USE）在
 * {@code CredentialService} 里判，二次确认在 {@code HostInventoryService} 里判。
 * 采集器拿到它只做一件事——把命令送过去，不再做任何权限判断。
 *
 * <p>明文口令/私钥只活在这个对象的生命周期里：{@link #toString()} 已打码，
 * 禁止把本对象或其字段写进日志、审计 detail 或异常消息。
 */
public record HostAccess(long projectId, String username, Long credentialId, String secret) {

    @Override
    public String toString() {
        return "HostAccess[projectId=" + projectId
                + ", username=" + (username == null || username.isBlank() ? "***" : username)
                + ", credentialId=" + credentialId
                + ", secret=***]";
    }
}
