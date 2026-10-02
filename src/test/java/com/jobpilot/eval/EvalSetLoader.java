package com.jobpilot.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评测集读取（PRD §9.2）。
 * <p>
 * 解析只此一份：{@link RetrievalEvalRunner} 用它跑评测，{@code EvalSetStructureTest} 用它做
 * 结构校验。分开写两份的话，评测集格式一变就会有一份悄悄漂移——而漂移的后果是
 * 「校验通过但评测执行器读不到用例」。
 */
final class EvalSetLoader {

    static final String CORPUS_RESOURCE = "/eval/eval-corpus.md";
    static final String DATASET_RESOURCE = "/eval/eval-set.jsonl";

    /** 语料段落 ID 形如 {@code ## P01 标题} */
    private static final Pattern PARAGRAPH_HEADING = Pattern.compile("^##\\s+(P\\d+)\\b");

    private static final String COMMENT_PREFIX = "#";
    private static final String ALT_COMMENT_PREFIX = "//";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** @param expected 期望命中的段落 ID；无答案型为空数组 */
    record EvalCase(String id, String type, String question, List<String> expected) {
    }

    private EvalSetLoader() {
    }

    /** 逐行读 JSONL；空行与 {@code #} / {@code //} 开头的注释行跳过，解析失败报行号 */
    static List<EvalCase> loadCases() {
        List<EvalCase> cases = new ArrayList<>();
        int lineNo = 0;
        for (String line : readResource(DATASET_RESOURCE).split("\n", -1)) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()
                    || trimmed.startsWith(COMMENT_PREFIX)
                    || trimmed.startsWith(ALT_COMMENT_PREFIX)) {
                continue;
            }
            cases.add(parseLine(trimmed, lineNo));
        }
        return cases;
    }

    private static EvalCase parseLine(String line, int lineNo) {
        try {
            Map<?, ?> node = MAPPER.readValue(line, Map.class);
            Object expected = node.get("expected");
            List<String> expectedIds = expected instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            return new EvalCase(
                    String.valueOf(node.get("id")),
                    String.valueOf(node.get("type")),
                    String.valueOf(node.get("question")),
                    expectedIds);
        } catch (IOException e) {
            throw new IllegalStateException("评测集第 " + lineNo + " 行解析失败：" + line, e);
        }
    }

    /** 语料里实际存在的段落 ID（有序，便于断言失败时比对） */
    static Set<String> corpusParagraphIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (String line : readResource(CORPUS_RESOURCE).split("\n", -1)) {
            Matcher matcher = PARAGRAPH_HEADING.matcher(line.trim());
            if (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }

    static String readResource(String path) {
        try (InputStream in = EvalSetLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("评测资源缺失：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取评测资源失败：" + path, e);
        }
    }
}
