package com.jobpilot.ai;

/**
 * 用户可见引用契约（PRD-FP-1.4）：[文档名 > 章节/段落] + Chunk 定位。
 */
public record Citation(
        String documentId,
        String documentName,
        String sectionPath,
        String chunkId,
        int charStart,
        int charEnd
) {

    /** 展示形式：文档名 > 章节路径 */
    public String display() {
        return documentName + " > " + sectionPath;
    }
}
