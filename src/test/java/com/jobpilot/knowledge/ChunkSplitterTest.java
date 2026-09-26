package com.jobpilot.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkSplitterTest {

    private final ChunkSplitter splitter = new ChunkSplitter();

    @Test
    void markdownKeepsHeadingPathAndCharRanges() {
        String md = """
                # 项目经历

                ## JobPilot 求职Copilot

                基于 Spring Boot 的 RAG 与 Agent 后端。

                ## 其他项目

                一段无关旧文本。
                """;
        List<ChunkPart> parts = splitter.split(md, "MARKDOWN", 500, 100);

        assertThat(parts).isNotEmpty();
        assertThat(parts.get(0).sectionPath()).isEqualTo("项目经历");
        assertThat(parts.stream().anyMatch(p -> p.sectionPath().equals("项目经历/JobPilot 求职Copilot"))).isTrue();

        // charStart 指向 chunk 首行在原文中的码点位置，chunk 文本逐行都能在原文中定位
        for (ChunkPart part : parts) {
            String[] lines = part.text().split("\n");
            assertThat(md).contains(lines[0]);
            assertThat(part.charStart()).isGreaterThanOrEqualTo(0);
            assertThat(part.charEnd()).isGreaterThan(part.charStart());
        }
    }

    @Test
    void plainTextSplitsBySizeWithOverlapOnLongLines() {
        String text = "甲".repeat(1200) + "\n" + "短段落。";
        List<ChunkPart> parts = splitter.split(text, "PLAIN_TEXT", 500, 100);

        assertThat(parts).hasSizeGreaterThanOrEqualTo(3);
        assertThat(parts.get(0).sectionPath()).isEqualTo("/");
        // 滑窗重叠：第二个窗口与第一个窗口尾部有重合内容
        assertThat(parts.get(1).charStart()).isLessThan(parts.get(0).charEnd());
        for (ChunkPart part : parts) {
            assertThat(part.text().codePointCount(0, part.text().length())).isLessThanOrEqualTo(500);
        }
    }

    @Test
    void blankContentProducesNoChunks() {
        assertThat(splitter.split("   \n\n  ", "PLAIN_TEXT", 500, 100)).isEmpty();
    }

    @Test
    void overlapMustBeSmallerThanChunkSize() {
        assertThatThrownBy(() -> splitter.split("x", "PLAIN_TEXT", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
