package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 단원 22개 × 개념 3 × 단계 3 = 198개 요청 전부(× 시드 5)에 대해 실제 서버 코드(PromptAssembler · ExampleBank)가 온전한 프롬프트와
 * 예시를 만드는지 본다 (11/03 모의 출제 스트레스 테스트). AI · DB 없이 돈다.
 */
class AllUnitsPromptTest {

    private static final int MAX_PROMPT_CHARS = 10_000;   // SYSTEM + USER. 지금 최대 약 6천 자 — 갑자기 두 배로 커지면 예시 · 범위 블록이 잘못 붙은 것
    private static final String[] LEVEL_LABEL = {null, "기초", "실전", "심화"};

    private final ExamUnits units = new ExamUnits();
    private final ExampleBank bank = new ExampleBank();
    private final PromptAssembler assembler = new PromptAssembler(new PromptTemplates(), bank);

    @Test
    void 단원_198개_요청이_모두_온전한_프롬프트와_예시_2개를_받는다() {
        int requests = 0;
        int maxChars = 0;
        for (ExamUnits.Unit u : units.units()) {
            for (ConceptRef c : u.concepts()) {
                List<ConceptRef> related = u.concepts().stream().filter(x -> !x.id().equals(c.id())).map(x -> new ConceptRef(null, x.name(), x.desc())).toList();
                for (int lv = 1; lv <= 3; lv++) {
                    for (int seed = 0; seed < 5; seed++) {
                        String where = u.id() + " " + c.id() + " lv" + lv + " seed " + seed;
                        PromptAssembler.Prompt p = assembler.build(new GenerateRequest(Persona.CERT_EXAM, u.subject(), u.name(), null, c, related, lv, 1, null, null, null).toInput(), seed);

                        assertThat(p.exampleIds()).as(where + " 예시 수").hasSize(2).doesNotHaveDuplicates();
                        assertThat(p.exampleFallback()).as(where + " 겹치는 예시로 채움").isFalse();
                        final int level = lv;
                        assertThat(p.exampleIds()).as(where + " 예시 id").allMatch(id -> id.startsWith("C" + level + "-"));
                        assertThat(p.system()).as(where + " SYSTEM 에 못 채운 변수").doesNotContain("{{", "}}");
                        assertThat(p.user()).as(where + " USER 에 못 채운 변수").doesNotContain("{{", "}}");
                        assertThat(p.system()).as(where).contains("[출제 범위] 정보처리산업기사", "[단계 사양]");
                        assertThat(p.user()).as(where).contains("[과목] " + u.subject(), "[단원] " + u.name(), "[시험] 정보처리산업기사", "[개념] " + c.name(),
                                "[단계] " + LEVEL_LABEL[lv] + " (lv " + lv + ")", "[관련 개념] ");
                        related.forEach(r -> assertThat(p.user()).as(where + " 관련 개념").contains(r.name()));

                        int chars = p.system().length() + p.user().length();
                        assertThat(chars).as(where + " 프롬프트 길이").isLessThan(MAX_PROMPT_CHARS);
                        maxChars = Math.max(maxChars, chars);
                        requests++;
                    }
                }
            }
        }
        assertThat(requests).isEqualTo(22 * 3 * 3 * 5);
        assertThat(maxChars).as("가장 긴 프롬프트").isGreaterThan(3000);   // 너무 짧으면 조각이 빠진 것
    }

    @Test
    void 같은_과목_예시를_하나도_못_받는_요청의_수를_더_늘리지_않는다() {
        // 요청의 개념 · 관련 개념이 그 칸의 유일한 같은 과목 예시와 겹치면 같은 과목 예시를 못 받는다 (11/03 기준 198개 중 15개).
        // 예시 은행을 넓혀 이 수가 줄면 아래 상한도 같이 낮출 것
        int none = 0;
        List<String> sys = new ArrayList<>();
        for (ExamUnits.Unit u : units.units()) {
            for (ConceptRef c : u.concepts()) {
                List<String> names = new ArrayList<>(List.of(c.name()));
                u.concepts().stream().filter(x -> !x.id().equals(c.id())).forEach(x -> names.add(x.name()));
                for (int lv = 1; lv <= 3; lv++) {
                    long same = bank.pick(Persona.CERT_EXAM, lv, names, u.subject(), 0, 2).entries().stream().filter(e -> e.subject().equals(u.subject())).count();
                    if (same == 0) {
                        none++;
                        if (u.subject().equals("정보시스템 기반 기술")) {
                            sys.add(u.id() + " " + c.id() + " lv" + lv);
                        }
                    }
                }
            }
        }
        assertThat(sys).as("정보시스템 과목은 칸마다 같은 과목 예시가 2개 이상이라 개념이 겹쳐도 하나는 남는다").isEmpty();
        assertThat(none).as("같은 과목 예시를 못 받는 요청 수").isLessThanOrEqualTo(15);
    }
}
