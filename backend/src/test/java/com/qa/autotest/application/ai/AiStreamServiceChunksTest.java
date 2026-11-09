package com.qa.autotest.application.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 流式分块：按固定字数切分且不丢字，保证前端拼回原文（EW33-07）。 */
class AiStreamServiceChunksTest {

    @Test
    void splitsWithoutLosingCharacters() {
        List<String> chunks = AiStreamService.chunks("事实：失败 3，通过率 97.0%。", 6);
        assertTrue(chunks.size() > 1);
        assertEquals("事实：失败 3，通过率 97.0%。", String.join("", chunks));
    }

    @Test
    void shortTextIsSingleChunk() {
        assertEquals(List.of("abc"), AiStreamService.chunks("abc", 24));
    }

    @Test
    void emptyTextProducesNoChunks() {
        assertTrue(AiStreamService.chunks("", 24).isEmpty());
    }
}
