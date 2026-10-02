package com.jobpilot.eval;

import com.jobpilot.eval.EvalSetLoader.EvalCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测集结构校验（PRD §9.2）——不依赖 MySQL / Ollama / Chroma，随日常 {@code mvn test} 一起跑。
 * <p>
 * {@link RetrievalEvalRunner} 默认跳过（需要真实基础设施），所以评测集的格式问题如果只靠它发现，
 * 就要等到手动跑评测那一刻。这里把「格式坏了」提前到每次提交：条数、类型配比、期望段落 ID
 * 是否真的存在于语料、无答案型是否留空——都是改了语料或数据集就可能踩的坑。
 */
class EvalSetStructureTest {

    private static final List<String> TYPES = List.of("FACTUAL", "COMPARATIVE", "SYNTHESIS", "NO_ANSWER");

    /** PRD §9.2 规定的配比：20 条 = 8 事实 / 5 比较 / 4 综合 / 3 无答案 */
    private static final Map<String, Long> EXPECTED_TYPE_COUNTS = Map.of(
            "FACTUAL", 8L,
            "COMPARATIVE", 5L,
            "SYNTHESIS", 4L,
            "NO_ANSWER", 3L);

    private final List<EvalCase> cases = EvalSetLoader.loadCases();
    private final Set<String> corpusIds = EvalSetLoader.corpusParagraphIds();

    @Test
    void datasetHasTwentyCasesWithUniqueIds() {
        assertThat(cases).hasSize(20);
        assertThat(cases).extracting(EvalCase::id).doesNotHaveDuplicates();
    }

    @Test
    void typeDistributionMatchesPrdTarget() {
        Map<String, Long> actual = cases.stream()
                .collect(Collectors.groupingBy(EvalCase::type, Collectors.counting()));

        assertThat(actual).isEqualTo(EXPECTED_TYPE_COUNTS);
    }

    @Test
    void everyCaseIsFullyPopulated() {
        for (EvalCase evalCase : cases) {
            // 字段缺失时读成字面量 "null"，这里一并挡住
            assertThat(evalCase.id()).as("id 缺失").isNotBlank().isNotEqualTo("null");
            assertThat(evalCase.type()).as("%s 的 type", evalCase.id()).isIn(TYPES);
            assertThat(evalCase.question()).as("%s 的 question", evalCase.id())
                    .isNotBlank().isNotEqualTo("null");
        }
    }

    /**
     * 期望段落 ID 必须真的存在于语料：期望指向不存在的段落时，评测永远命中不了，
     * 但执行器只会报「未命中」，看不出根因在数据集而不在检索。
     * <p>
     * 不要求语料段落被全覆盖——未被引用的段落是<b>有意留的干扰项</b>，
     * 检索若把它们排进 top-5，评测自然判未命中。
     */
    @Test
    void expectedParagraphIdsAllExistInCorpus() {
        Set<String> referenced = cases.stream()
                .flatMap(evalCase -> evalCase.expected().stream())
                .collect(Collectors.toSet());

        assertThat(referenced)
                .as("语料实际段落：%s", corpusIds)
                .isSubsetOf(corpusIds);
    }

    /** 无答案型靠「期望为空」表达，非无答案型靠「期望非空」表达——写反了评测会静默全过或全挂 */
    @Test
    void noAnswerCasesHaveEmptyExpectationAndOthersDoNot() {
        for (EvalCase evalCase : cases) {
            if ("NO_ANSWER".equals(evalCase.type())) {
                assertThat(evalCase.expected()).as("%s 是无答案型，期望应为空", evalCase.id()).isEmpty();
            } else {
                assertThat(evalCase.expected()).as("%s 期望段落不能为空", evalCase.id()).isNotEmpty();
            }
        }
    }

    @Test
    void questionsAreDistinct() {
        assertThat(cases).extracting(EvalCase::question).doesNotHaveDuplicates();
    }
}
