package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

class UnitQuestionServiceTest {

    private static final PromptTemplates TEMPLATES = new PromptTemplates();
    private static final ExamUnits UNITS = new ExamUnits();

    /** 가짜 모델을 감싸 받은 요청을 기록한다 (칸마다 병렬로 불리므로 스레드 안전한 목록). */
    private static final class Recording implements ModelClient {
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final ModelClient inner;

        Recording(ModelClient inner) {
            this.inner = inner;
        }

        @Override
        public Reply invoke(Request request) {
            requests.add(request);
            return inner.invoke(request);
        }

        List<Request> generations() {
            return requests.stream().filter(r -> !r.system().startsWith("너는 시험 문제를 검토하는")).toList();
        }
    }

    private static UnitQuestionService service(ModelClient client) {
        QuestionGenerationService generator = new QuestionGenerationService(new PromptAssembler(TEMPLATES, new ExampleBank()), client,
                new QuestionVerifier(TEMPLATES), () -> 0, () -> new Random(1));
        return new UnitQuestionService(UNITS, generator);
    }

    @Test
    void 요청이_없으면_개념_3개_곱하기_1_2_3단계로_9문항을_만든다() {
        Recording model = new Recording(new MockModelClient());
        UnitQuestionService.UnitQuestions r = service(model).generate("sys-net", null);

        assertThat(r.exam()).isEqualTo("정보처리산업기사");
        assertThat(r.unit().name()).isEqualTo("네트워크 기초 활용");
        assertThat(r.requested()).isEqualTo(9);
        assertThat(r.passed()).isEqualTo(9);
        assertThat(r.cells()).hasSize(9).allMatch(c -> c.result().status().equals("pass"));
        assertThat(r.cells()).extracting(c -> c.conceptId() + "-" + c.level())
                .containsExactly("c1-1", "c1-2", "c1-3", "c2-1", "c2-2", "c2-3", "c3-1", "c3-2", "c3-3");
        assertThat(r.cells()).allMatch(c -> c.result().questions().get(0).verify().verified());   // 2차 풀이까지 거쳤다
    }

    @Test
    void 수험생_역할로_과목_단원_시험_출제범위를_넣고_3단계는_같은_단원의_다른_개념을_연결한다() {
        Recording model = new Recording(new MockModelClient());
        service(model).generate("db-sql-adv", new UnitQuestionService.UnitRequest(List.of(3), 1, null));

        List<ModelClient.Request> gens = model.generations();
        assertThat(gens).hasSize(3).allMatch(r -> r.tier() == ModelClient.Tier.ADVANCED);
        for (ModelClient.Request g : gens) {
            String user = g.messages().get(0).text();
            assertThat(user).contains("[과목] 데이터베이스 활용     [단원] 고급 SQL 작성", "[시험] 정보처리산업기사", "[단계] 심화 (lv 3)");
            assertThat(g.system()).contains("[출제 범위] 정보처리산업기사");
        }
        // [관련 개념] = 같은 단원의 나머지 두 개념 (단원표에서 만든 기대값 — 개념을 고쳐도 테스트는 그대로)
        List<ConceptRef> cs = UNITS.find("db-sql-adv").orElseThrow().concepts();
        String first = gens.stream().map(g -> g.messages().get(0).text()).filter(u -> u.contains("[개념] " + cs.get(0).name() + " —")).findFirst().orElseThrow();
        assertThat(first).contains("[관련 개념] " + cs.get(1).name() + " — " + cs.get(1).desc() + " / " + cs.get(2).name() + " — " + cs.get(2).desc());
    }

    @Test
    void 단계와_칸마다_문항_수를_고를_수_있고_전체는_15문항까지다() {
        UnitQuestionService s = service(new MockModelClient());
        UnitQuestionService.UnitQuestions two = s.generate("lang-basic", new UnitQuestionService.UnitRequest(List.of(2, 1, 2), 2, null));
        assertThat(two.requested()).isEqualTo(12);   // 개념 3 × 단계 2(중복은 하나로) × 2문항
        assertThat(two.cells()).extracting(UnitQuestionService.Cell::level).containsExactly(1, 2, 1, 2, 1, 2);
        assertThat(two.passed()).isEqualTo(12);

        assertThatThrownBy(() -> s.generate("lang-basic", new UnitQuestionService.UnitRequest(null, 2, null))).hasMessageContaining("15개 이하");
        assertThatThrownBy(() -> s.generate("lang-basic", new UnitQuestionService.UnitRequest(List.of(4), 1, null))).hasMessageContaining("levels");
        assertThatThrownBy(() -> s.generate("lang-basic", new UnitQuestionService.UnitRequest(List.of(), 1, null))).hasMessageContaining("비어");
        assertThatThrownBy(() -> s.generate("lang-basic", new UnitQuestionService.UnitRequest(null, 0, null))).hasMessageContaining("count");
    }

    @Test
    void 없는_단원은_찾을_수_없다고_알린다() {
        assertThatThrownBy(() -> service(new MockModelClient()).generate("nope", null)).isInstanceOf(UnitQuestionService.UnitNotFoundException.class);
    }

    @Test
    void 모델_호출이_실패하면_감싸지_않고_그대로_올린다() {
        ModelClient failing = request -> {
            throw new ModelCallException("throttled", null);
        };
        assertThatThrownBy(() -> service(failing).generate("sys-os", null)).isInstanceOf(ModelCallException.class);
    }
}
