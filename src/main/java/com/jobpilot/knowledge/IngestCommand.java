package com.jobpilot.knowledge;

/** 导入命令：content 为已抽取的文本（M-1 支持 md/txt 文本粘贴） */
public record IngestCommand(
        String userId,
        String name,
        String docType,
        String tags,
        String content
) {
}
