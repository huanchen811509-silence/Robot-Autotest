package com.qa.autotest.application.caseasset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** ED202 只读快照与仓库内文本固件的路径解析（surefire cwd = backend/，也兼容仓库根执行）。 */
final class Ed202Fixtures {

    static final String SNAPSHOT_SHA256 =
            "9b897b77564eb6d9cb6843fa350b1cd085e61d613a1b047e8ce5ceaa36316e39";

    private Ed202Fixtures() {
    }

    static Path snapshot() {
        return resolve("fixtures/ed202/ED202测试用例_V0.4.xlsx");
    }

    static InputStream snapshotStream() throws IOException {
        return Files.newInputStream(snapshot());
    }

    static Path repoFile(String relative) {
        return resolve(relative);
    }

    private static Path resolve(String relative) {
        Path fromBackend = Path.of("..", relative);
        if (Files.isRegularFile(fromBackend)) {
            return fromBackend;
        }
        Path fromRoot = Path.of(relative);
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("找不到 " + relative + "；cwd=" + Path.of("").toAbsolutePath());
    }
}
