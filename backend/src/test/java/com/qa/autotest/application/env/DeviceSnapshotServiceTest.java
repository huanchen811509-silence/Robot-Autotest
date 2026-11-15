package com.qa.autotest.application.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.DeviceSnapshotRepository;
import com.qa.autotest.domain.host.HostInventoryRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** W65-01/02/04：内容寻址、不填假值、按项对比三态。 */
class DeviceSnapshotServiceTest {

    private final DeviceSnapshotRepository repository = mock(DeviceSnapshotRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeviceSnapshotService service = new DeviceSnapshotService(repository, mapper);

    private static HostInventoryRecord record(String json) {
        return new HostInventoryRecord(7L, 3L, 11L, "127.0.0.1", Instant.parse("2026-11-15T10:00:00Z"),
                "PARTIAL", json, "spec-hash", 2, 1, 100);
    }

    private static String spec(String roceValue, String gpuReason) {
        return "{\"host\":\"127.0.0.1\",\"items\":["
                + "{\"category\":\"ROCE\",\"command\":\"cat rate\",\"value\":" + roceValue
                + ",\"reason\":null},"
                + "{\"category\":\"GPU\",\"command\":\"nvidia-smi\",\"value\":null,\"reason\":\""
                + gpuReason + "\"}]}";
    }

    @Test
    void sameContentHasSameHashAndDoesNotDependOnArrayOrder() throws Exception {
        String a = spec("100", "no gpu");
        String b = spec("100", "no gpu");
        assertEquals(DeviceSnapshotService.hashOf(DeviceSnapshotService.flatten(mapper.readTree(a))),
                DeviceSnapshotService.hashOf(DeviceSnapshotService.flatten(mapper.readTree(b))));
        assertNotNull(DeviceSnapshotService.hashOf(DeviceSnapshotService.flatten(mapper.readTree(a))));
        assertEquals(64, DeviceSnapshotService.sha256("x").length());
    }

    @Test
    void missingItemKeepsNullValueAndReason() throws Exception {
        List<DeviceSnapshotService.Draft> drafts =
                DeviceSnapshotService.flatten(mapper.readTree(spec("100", "nvidia-smi not found")));
        DeviceSnapshotService.Draft gpu = drafts.stream()
                .filter(d -> d.category().equals("GPU")).findFirst().orElseThrow();
        assertFalse(gpu.collected());
        assertNull(gpu.valueJson());
        assertEquals("nvidia-smi not found", gpu.reason());
        DeviceSnapshotService.Draft roce = drafts.stream()
                .filter(d -> d.category().equals("ROCE")).findFirst().orElseThrow();
        assertEquals("100", roce.valueJson());
        assertEquals(64, roce.valueHash().length());
    }

    @Test
    void captureWritesOneSnapshotAndFlattenedItems() {
        when(repository.upsertSnapshot(any(), any())).thenReturn(91L);
        long id = service.capture(3L, record(spec("100", "no gpu")));
        assertEquals(91L, id);
        verify(repository).insertItems(eq(3L), eq(91L), any());
    }

    @Test
    void changedContentProducesDifferentHash() throws Exception {
        String a = DeviceSnapshotService.hashOf(
                DeviceSnapshotService.flatten(mapper.readTree(spec("100", "no gpu"))));
        String b = DeviceSnapshotService.hashOf(
                DeviceSnapshotService.flatten(mapper.readTree(spec("400", "no gpu"))));
        assertFalse(a.equals(b));
    }
}
