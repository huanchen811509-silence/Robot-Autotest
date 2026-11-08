package com.qa.autotest.application.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行域运行参数。默认值与 v0.4 spec §3.2 一致，可由 application.yml 的 execution.* 覆盖。
 */
@Component
@ConfigurationProperties(prefix = "execution")
public class ExecutionProperties {

    private String workspaceRoot = "~/robot-test/infra-monitor";
    private String artifactsRoot = "~/robot-test/artifacts";
    private String venvBin = "~/rf-stack/.venv/bin";
    /**
     * pytest 可执行文件（v2.7）：与 RF 的 pabot 不同源——pytest 运行时
     * （pytest 9.x + json-report + xdist）在独立 venv，可由 execution.pytest-bin /
     * 环境变量 EXECUTION_PYTEST_BIN 覆盖；为空时回落 venvBin/pytest。
     */
    private String pytestBin = "";
    private int maxParallel = 4;
    private int leaseTtlSeconds = 120;
    private int leaseHeartbeatSeconds = 40;
    private int leaseReclaimSeconds = 60;
    private int leaseMaxWaitSeconds = 60;
    private int runTimeoutSeconds = 3600;
    /** 单用例超时秒数（FR-v0.4-24）；<=0 表示不注入 Test Timeout。 */
    private int testTimeoutSeconds = 1800;
    private int inactivityTimeoutSeconds = 900;
    private long minFreeBytes = 5L * 1024 * 1024 * 1024;
    private int availablePorts = 32;
    private String pabotVersionPrefix = "5.2";
    private boolean schedulingEnabled = true;
    private List<FailureRuleConfig> failureRules = new ArrayList<>();
    private DutConfig dut = new DutConfig();

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String getArtifactsRoot() {
        return artifactsRoot;
    }

    public void setArtifactsRoot(String artifactsRoot) {
        this.artifactsRoot = artifactsRoot;
    }

    public String getVenvBin() {
        return venvBin;
    }

    public void setVenvBin(String venvBin) {
        this.venvBin = venvBin;
    }

    public String getPytestBin() {
        return pytestBin;
    }

    public void setPytestBin(String pytestBin) {
        this.pytestBin = pytestBin;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }

    public int getLeaseTtlSeconds() {
        return leaseTtlSeconds;
    }

    public void setLeaseTtlSeconds(int leaseTtlSeconds) {
        this.leaseTtlSeconds = leaseTtlSeconds;
    }

    public int getLeaseHeartbeatSeconds() {
        return leaseHeartbeatSeconds;
    }

    public void setLeaseHeartbeatSeconds(int leaseHeartbeatSeconds) {
        this.leaseHeartbeatSeconds = leaseHeartbeatSeconds;
    }

    public int getLeaseReclaimSeconds() {
        return leaseReclaimSeconds;
    }

    public void setLeaseReclaimSeconds(int leaseReclaimSeconds) {
        this.leaseReclaimSeconds = leaseReclaimSeconds;
    }

    public int getLeaseMaxWaitSeconds() {
        return leaseMaxWaitSeconds;
    }

    public void setLeaseMaxWaitSeconds(int leaseMaxWaitSeconds) {
        this.leaseMaxWaitSeconds = leaseMaxWaitSeconds;
    }

    public int getRunTimeoutSeconds() {
        return runTimeoutSeconds;
    }

    public void setRunTimeoutSeconds(int runTimeoutSeconds) {
        this.runTimeoutSeconds = runTimeoutSeconds;
    }

    public int getTestTimeoutSeconds() {
        return testTimeoutSeconds;
    }

    public void setTestTimeoutSeconds(int testTimeoutSeconds) {
        this.testTimeoutSeconds = testTimeoutSeconds;
    }

    public int getInactivityTimeoutSeconds() {
        return inactivityTimeoutSeconds;
    }

    public void setInactivityTimeoutSeconds(int inactivityTimeoutSeconds) {
        this.inactivityTimeoutSeconds = inactivityTimeoutSeconds;
    }

    public long getMinFreeBytes() {
        return minFreeBytes;
    }

    public void setMinFreeBytes(long minFreeBytes) {
        this.minFreeBytes = minFreeBytes;
    }

    public int getAvailablePorts() {
        return availablePorts;
    }

    public void setAvailablePorts(int availablePorts) {
        this.availablePorts = availablePorts;
    }

    public String getPabotVersionPrefix() {
        return pabotVersionPrefix;
    }

    public void setPabotVersionPrefix(String pabotVersionPrefix) {
        this.pabotVersionPrefix = pabotVersionPrefix;
    }

    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    public void setSchedulingEnabled(boolean schedulingEnabled) {
        this.schedulingEnabled = schedulingEnabled;
    }

    public List<FailureRuleConfig> getFailureRules() {
        return failureRules;
    }

    public void setFailureRules(List<FailureRuleConfig> failureRules) {
        this.failureRules = failureRules == null ? new ArrayList<>() : failureRules;
    }

    public DutConfig getDut() {
        return dut;
    }

    public void setDut(DutConfig dut) {
        this.dut = dut == null ? new DutConfig() : dut;
    }

    public record FailureRuleConfig(String failureClass, List<String> keywords, List<String> regex) {
    }

    public static class DutConfig {
        private String jumpHost = "192.168.180.2";
        private String jumpUser = "evergreen";
        private int connectTimeoutSeconds = 5;
        private int commandTimeoutSeconds = 15;

        public String getJumpHost() {
            return jumpHost;
        }

        public void setJumpHost(String jumpHost) {
            this.jumpHost = jumpHost;
        }

        public String getJumpUser() {
            return jumpUser;
        }

        public void setJumpUser(String jumpUser) {
            this.jumpUser = jumpUser;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getCommandTimeoutSeconds() {
            return commandTimeoutSeconds;
        }

        public void setCommandTimeoutSeconds(int commandTimeoutSeconds) {
            this.commandTimeoutSeconds = commandTimeoutSeconds;
        }
    }
}
