package com.qa.autotest.application.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.common.BusinessException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** EvidenceBundleService 单测：@TempDir 真实临时文件系统，DB 用 mock，不依赖真实数据库。 */
class EvidenceBundleServiceTest {

    @TempDir
    Path tempDir;

    private EvidencePaths paths;
    private EvidenceBundleStore repository;
    private EvidenceBundleService service;

    @BeforeEach
    void setUp() {
        // 源文件写在 tempDir 根下，证据库单独放 store/ —— 否则 objectCount() 会把
        // 源文件本身数成对象文件（同内容去重用例因此假红）。真实部署同样是两个目录。
        paths = EvidencePaths.of(tempDir.resolve("store"));
        repository = mock(EvidenceBundleStore.class);
        when(repository.find(anyString())).thenReturn(Optional.empty());
        // W57-51 起哈希不符要留审计，构造器多一个 AuditService；单测用 mock，不连库。
        service = new EvidenceBundleService(paths, repository,
                mock(com.qa.autotest.application.iam.AuditService.class));
    }

    private Path file(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    /** 只数「对象文件」，排除临时目录与指针目录。 */
    private long objectCount() throws Exception {
        try (var walk = Files.walk(paths.root())) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(paths.tempDir()))
                    .filter(p -> !p.startsWith(paths.pointerDir()))
                    .count();
        }
    }

    @Test
    @DisplayName("写入：文件名=内容 sha256，ab/cd 两级分片，状态 ready")
    void write_namesByShaAndShards() throws Exception {
        Path source = file("a.txt", "hello-evidence");
        String sha = EvidencePaths.sha256Of(source);

        EvidenceBundleService.BundleDescriptor descriptor =
                service.write("report:RUN-1", List.of(source));

        Path object = paths.objectFile(sha);
        assertTrue(Files.isRegularFile(object));
        assertEquals(sha, object.getFileName().toString());
        assertEquals(sha.substring(0, 2),
                object.getParent().getParent().getFileName().toString());
        assertEquals(sha.substring(2, 4), object.getParent().getFileName().toString());
        assertTrue(Files.isRegularFile(paths.pointerFile("report:RUN-1")));
        assertEquals(1, descriptor.fileCount());
        assertEquals("ready", descriptor.status());
    }

    @Test
    @DisplayName("同内容去重：两个同内容文件只落一个对象")
    void write_deduplicatesSameContent() throws Exception {
        Path a = file("a.txt", "same-content");
        Path b = file("b.txt", "same-content");

        service.write("k", List.of(a, b));

        assertEquals(1, objectCount());
    }

    @Test
    @DisplayName("原子发布后经过 building→ready，且不写 failed")
    void write_statusTransitions() throws Exception {
        Path source = file("a.txt", "x");

        service.write("k", List.of(source));

        verify(repository).begin(eq("k"), any());
        verify(repository).markReady(eq("k"), anyString(), eq(1), anyLong());
        verify(repository, never()).markFailed(anyString());
    }

    @Test
    @DisplayName("读时重算 sha256：篡改对象文件拒读")
    void read_rejectsTamperedObject() throws Exception {
        Path source = file("a.txt", "original");
        service.write("k", List.of(source));
        String sha = EvidencePaths.sha256Of(source);
        Files.writeString(paths.objectFile(sha), "tampered", StandardCharsets.UTF_8);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.read("k"));

        assertTrue(ex.getMessage().contains("校验失败"));
    }

    @Test
    @DisplayName("单文件 >512MB 直接拒绝，且不开 building")
    void write_rejectsOver512Mb() throws Exception {
        Path big = tempDir.resolve("big.bin");
        try (RandomAccessFile raf = new RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(EvidenceBundleService.MAX_FILE_BYTES + 1);
        }

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.write("k", List.of(big)));

        assertTrue(ex.getMessage().contains("512MB"));
        verify(repository, never()).begin(anyString(), any());
    }

    @Test
    @DisplayName("旧扁平目录 <root>/<sha> 读兼容")
    void read_legacyFlatCompatible() throws Exception {
        String content = "legacy-data";
        Path source = file("legacy.txt", content);
        String sha = EvidencePaths.sha256Of(source);
        Files.createDirectories(paths.pointerDir());
        Files.writeString(paths.legacyFlat(sha), content, StandardCharsets.UTF_8);
        String pointer = sha + "\t" + content.getBytes(StandardCharsets.UTF_8).length
                + "\tlegacy.txt\n";
        Files.writeString(paths.pointerFile("k"), pointer, StandardCharsets.UTF_8);

        List<EvidenceBundleService.EvidenceEntry> entries = service.read("k");

        assertEquals(1, entries.size());
        assertEquals(content, new String(entries.get(0).content(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("verify：正常 ok，篡改后 ok=false")
    void verify_okAndDetectsTamper() throws Exception {
        Path source = file("a.txt", "v");
        service.write("k", List.of(source));
        assertTrue(service.verify("k").ok());
        assertEquals(1, service.verify("k").checked());

        String sha = EvidencePaths.sha256Of(source);
        Files.writeString(paths.objectFile(sha), "bad", StandardCharsets.UTF_8);
        assertFalse(service.verify("k").ok());
    }

    @Test
    @DisplayName("写入异常置 failed 并抛业务异常")
    void write_marksFailedOnError() throws Exception {
        Path source = file("a.txt", "v");
        doThrow(new IllegalStateException("db down"))
                .when(repository).markReady(anyString(), anyString(), anyInt(), anyLong());

        assertThrows(BusinessException.class, () -> service.write("k", List.of(source)));

        verify(repository).markFailed("k");
    }

    @Test
    @DisplayName("并发写同内容：最终只有一个对象且可校验读")
    void write_concurrentSameContent() throws Exception {
        Path source = file("a.txt", "concurrent");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    service.write("k", List.of(source));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, objectCount());
        assertTrue(service.verify("k").ok());
    }
}
