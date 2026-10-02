package com.jobpilot.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chunk 切分（PRD-FP-1.2 的暴力实现）：
 * <ul>
 *   <li>Markdown：按标题行分节并保留章节路径，节内逐行累积；纯文本整篇一节；</li>
 *   <li>累积内容接近 chunkSize 即投递为一个 Chunk；单行超限按字符滑窗切分，窗口重叠 overlap；</li>
 *   <li>每个 Chunk 携带其在整篇原文中的绝对码点区间 [charStart, charEnd)，引用定位可复现；</li>
 *   <li>charStart 为 -1 表示精确定位失败（兜底，不影响检索，仅弱化引用定位强度）。</li>
 * </ul>
 * chunkSize/overlap 先取默认值，M-1 评测集跑完后再调。
 */
@Component
public class ChunkSplitter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*$");
    static final String SECTION_SEPARATOR = "/";

    public List<ChunkPart> split(String content, String docType, int chunkSize, int overlap) {
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 必须小于 chunkSize");
        }
        List<ChunkPart> parts = new ArrayList<>();
        int[] seq = {0};
        for (Section section : sections(content, docType)) {
            flush(section, parts, seq, chunkSize, overlap);
        }
        return parts;
    }

    private record RawChunk(String sectionPath, String text, int charStart) {
    }

    private record Section(String path, List<Line> lines) {
    }

    private record Line(String text, int startCp) {
    }

    /** 把原文切成带章节路径的节；Markdown 按标题行分节，纯文本一节到底 */
    private List<Section> sections(String content, String docType) {
        List<Section> sections = new ArrayList<>();
        if (!"MARKDOWN".equals(docType)) {
            sections.add(new Section(SECTION_SEPARATOR, linesWithOffsets(content)));
            return sections;
        }
        String[] headings = new String[6];
        String currentPath = SECTION_SEPARATOR;
        List<Line> current = new ArrayList<>();
        int cursor = 0;
        for (String line : content.split("\n", -1)) {
            Matcher m = HEADING.matcher(line);
            boolean isHeading = m.matches();
            if (isHeading) {
                if (!current.isEmpty()) {
                    sections.add(new Section(currentPath, current));
                    current = new ArrayList<>();
                }
                int level = m.group(1).length();
                headings[level - 1] = m.group(2);
                for (int i = level; i < headings.length; i++) {
                    headings[i] = null;
                }
                currentPath = pathOf(headings, level);
            }
            if (!line.isBlank()) {
                // 只收录非空行，行起点就是自身首字符；空行作为块内分隔符在投递时补回
                current.add(new Line(line, cursor));
            }
            cursor += line.codePointCount(0, line.length()) + 1; // +1 是被 split 吃掉的换行符
        }
        if (!current.isEmpty()) {
            sections.add(new Section(currentPath, current));
        }
        return sections;
    }

    private List<Line> linesWithOffsets(String content) {
        List<Line> lines = new ArrayList<>();
        int cursor = 0;
        for (String line : content.split("\n", -1)) {
            if (!line.isBlank()) {
                lines.add(new Line(line, cursor));
            }
            cursor += line.codePointCount(0, line.length()) + 1;
        }
        return lines;
    }

    private String pathOf(String[] headings, int level) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) {
            if (headings[i] != null) {
                sb.append(sb.isEmpty() ? "" : SECTION_SEPARATOR).append(headings[i]);
            }
        }
        return sb.isEmpty() ? SECTION_SEPARATOR : sb.toString();
    }

    /** 节内逐行累积成 RawChunk；单行超限走滑窗；最后统一换算绝对码点区间 */
    private void flush(Section section, List<ChunkPart> parts, int[] seq, int chunkSize, int overlap) {
        List<RawChunk> raw = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int bufStart = -1;
        int bufCp = 0;
        for (Line line : section.lines()) {
            // Line.text 已含合并进来的前导空行，码点数即其长度贡献（投递时额外补的 '\n' 不计入约束）
            int lineCp = line.text().codePointCount(0, line.text().length());
            if (lineCp > chunkSize) {
                if (bufCp > 0) {
                    raw.add(new RawChunk(section.path(), buf.toString(), bufStart));
                    buf.setLength(0);
                    bufCp = 0;
                }
                slide(line, section.path(), chunkSize, overlap, raw);
                continue;
            }
            if (bufCp + lineCp > chunkSize) {
                raw.add(new RawChunk(section.path(), buf.toString(), bufStart));
                buf.setLength(0);
                bufCp = 0;
            }
            if (bufCp == 0) {
                bufStart = line.startCp();
            }
            buf.append(line.text()).append('\n');
            bufCp += lineCp;
        }
        if (bufCp > 0) {
            raw.add(new RawChunk(section.path(), buf.toString(), bufStart));
        }
        for (RawChunk rc : raw) {
            String text = rc.text();
            int lead = 0;
            while (lead < text.length() && Character.isWhitespace(text.charAt(lead))) {
                lead++;
            }
            int trail = text.length();
            while (trail > lead && Character.isWhitespace(text.charAt(trail - 1))) {
                trail--;
            }
            String stripped = text.substring(lead, trail);
            if (stripped.isBlank()) {
                continue;
            }
            int leadCp = text.substring(0, lead).codePointCount(0, lead);
            int start = rc.charStart() + leadCp;
            int end = start + stripped.codePointCount(0, stripped.length());
            parts.add(new ChunkPart(seq[0]++, rc.sectionPath(), stripped, start, end));
        }
    }

    /** 超长单行滑窗：窗口步长 = chunkSize - overlap，窗口天然重叠 */
    private void slide(Line line, String sectionPath, int chunkSize, int overlap, List<RawChunk> raw) {
        String text = line.text();
        int total = text.codePointCount(0, text.length());
        int step = chunkSize - overlap;
        int cursorCp = 0;
        int cursorChar = 0;
        while (cursorCp < total) {
            int endCp = Math.min(cursorCp + chunkSize, total);
            int endChar = text.offsetByCodePoints(cursorChar, endCp - cursorCp);
            raw.add(new RawChunk(sectionPath, text.substring(cursorChar, endChar),
                    line.startCp() + cursorCp));
            cursorCp += step;
            if (cursorCp >= total) {
                break;
            }
            cursorChar = text.offsetByCodePoints(0, Math.min(cursorCp, total));
        }
    }
}
