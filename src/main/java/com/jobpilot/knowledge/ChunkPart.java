package com.jobpilot.knowledge;

/**
 * 切分产物：顺序号、章节路径、文本及其在原文中的字符区间（引用定位用）。
 */
public record ChunkPart(
        int seq,
        String sectionPath,
        String text,
        int charStart,
        int charEnd
) {
}
