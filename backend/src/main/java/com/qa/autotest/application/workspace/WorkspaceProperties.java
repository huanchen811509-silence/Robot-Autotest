package com.qa.autotest.application.workspace;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 工作区配置（前缀 {@code app.workspace}），口径见 docs/contracts/workspace-bundle.md §7。 */
@Component
@ConfigurationProperties(prefix = "app.workspace")
public class WorkspaceProperties {

    public static final int DEFAULT_MAX_FILES = 20000;
    public static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;
    public static final long DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024;
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** 唯一工作区根（ADR-4X-15）；相对路径按进程工作目录解析。 */
    private String root = "./workspace";

    /** HMAC 密钥（64 位十六进制）；空则用 <root>/.signing-key（服务端自动生成，0600）。 */
    private String signingKey = "";

    private int maxFiles = DEFAULT_MAX_FILES;
    private long maxBytes = DEFAULT_MAX_BYTES;
    private long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    public Path rootPath() {
        return Paths.get(root).toAbsolutePath().normalize();
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public String getSigningKey() {
        return signingKey;
    }

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
