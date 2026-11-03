package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MockModelClientTest {

    private static final PromptTemplates TEMPLATES = new PromptTemplates();
    // 2차 풀이까지 켠 상태 — 가짜 모델은 검증 요청에 자기가 낸 정답 위치를 답한다
    private final QuestionGenerationService service = new QuestionGenerationService(
            new PromptAssembler(TEMPLATES, new ExampleBank()), new MockModelClient(), new QuestionVerifier(TEMPLATES), () -> 0, () -> new Random(3));

    private GenerateResult run(Persona persona, int level, int count) {
        return service.generate(new GenerateRequest(persona, "정보처리기사 필기", "데이터베이스", persona == Persona.CERT_EXAM ? "정보처리기사 필기" : null,
                new ConceptRef("c1", "정규화", "중복을 줄이는 설계"), List.of(new ConceptRef(null, "함수적 종속", "결정 관계")), level, count, null, null, null));
    }

    @Test
    void 가짜_모델_응답은_모든_역할_단계_문항수에서_검사를_한_번에_통과한다() {
        for (Persona p : Persona.values()) {
            for (int lv = 1; lv <= 3; lv++) {
                for (int count : new int[] {1, 3, 5}) {
                    GenerateResult r = run(p, lv, count);
                    String where = p + " lv" + lv + " x" + count;
                    assertThat(r.status()).as(where + " " + r.attempts()).isEqualTo("pass");
                    assertThat(r.questions()).as(where).hasSize(count);
                    assertThat(r.attempts()).as(where).hasSize(1);
                    assertThat(r.questions()).as(where).allMatch(q -> q.verify().verified() && q.verify().stageOk());
                }
            }
        }
    }

    @Test
    void 여러_문항은_서로_다르고_가짜라는_표시가_있다() {
        GenerateResult r = run(Persona.UNIVERSITY, 2, 5);
        Set<String> questions = new HashSet<>();
        r.questions().forEach(q -> {
            questions.add(q.q());
            assertThat(q.q()).startsWith("[모의 ");
            assertThat(q.x()).contains("가짜");
            assertThat(q.o().get(q.a())).startsWith("[모의]");
        });
        assertThat(questions).hasSize(5);
    }

    @Test
    void 가짜_모델이_고른_정답_위치는_섞인_뒤에도_유지된다() {
        GenerateResult r = run(Persona.CERT_EXAM, 3, 4);
        r.questions().forEach(q -> {
            assertThat(q.distractors().keySet()).doesNotContain(q.a()).hasSize(3);
            assertThat(q.concepts()).containsExactly("정규화", "함수적 종속");
            assertThat(q.steps()).hasSize(3);
        });
    }
}
