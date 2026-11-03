package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ObjectNode;

class QuestionGenerationServiceTest {

    /** 미리 정해 둔 응답을 차례로 돌려주고, 받은 요청을 기록하는 가짜 모델. */
    static final class Scripted implements ModelClient {
        final Deque<String> replies = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();

        Scripted(String... replies) {
            this.replies.addAll(List.of(replies));
        }

        @Override
        public Reply invoke(Request request) {
            requests.add(request);
            return new Reply(replies.isEmpty() ? "" : replies.poll(), "end_turn");
        }
    }

    private static final PromptTemplates TEMPLATES = new PromptTemplates();

    /** 2차 풀이를 끈 서비스 — 생성 · 게이트 ① ② · 재생성만 본다. */
    private static QuestionGenerationService service(ModelClient client) {
        return new QuestionGenerationService(new PromptAssembler(TEMPLATES, new ExampleBank()), client, null, () -> 0, () -> new Random(1));
    }

    /** 2차 풀이를 켠 서비스. */
    private static QuestionGenerationService verified(ModelClient client) {
        return new QuestionGenerationService(new PromptAssembler(TEMPLATES, new ExampleBank()), client, new QuestionVerifier(TEMPLATES), () -> 0, () -> new Random(1));
    }

    /** 생성 요청에는 정해 둔 응답을, 2차 풀이 요청에는 judge 가 만든 답을 돌려준다. 검증은 병렬로 올 수 있어 기록은 동기화한다. */
    static final class Routed implements ModelClient {
        final Deque<String> generations = new ArrayDeque<>();
        final java.util.function.BiFunction<String, Integer, String> judge;   // (검증 요청 글, 몇 번째 검증 호출) → 검증자 응답
        final List<Request> genRequests = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Request> verifyRequests = java.util.Collections.synchronizedList(new ArrayList<>());

        Routed(java.util.function.BiFunction<String, Integer, String> judge, String... generations) {
            this.judge = judge;
            this.generations.addAll(List.of(generations));
        }

        @Override
        public synchronized Reply invoke(Request request) {
            if (request.system().startsWith("너는 시험 문제를 검토하는")) {
                verifyRequests.add(request);
                return new Reply(judge.apply(request.messages().get(0).text(), verifyRequests.size()), "end_turn");
            }
            genRequests.add(request);
            return new Reply(generations.isEmpty() ? "" : generations.poll(), "end_turn");
        }
    }

    /** 검증자가 출제자와 같은 답을 고른다 — 지문에 따라 정답 위치를 알려 준다. */
    private static String answer(int correct, int stage) {
        return "{\"correct\": [" + correct + "], \"grounded\": true, \"stage\": " + stage + ", \"issue\": \"\"}";
    }

    private static GenerateRequest request(int level, int count) {
        return new GenerateRequest(Persona.UNIVERSITY, "자료구조", "스택과 큐", null, new ConceptRef("c1", "스택", "LIFO"),
                List.of(new ConceptRef(null, "큐", "FIFO")), level, count, null, null, null);
    }

    private static ObjectNode broken() {
        ObjectNode it = TestItems.item(1);
        ((tools.jackson.databind.node.ArrayNode) it.get("o")).remove(3);   // 보기 3개
        return it;
    }

    @Test
    void 한_번에_통과하면_pass_이고_정답_위치가_섞인다() {
        Scripted model = new Scripted(TestItems.reply(TestItems.item(1)));
        GenerateResult r = service(model).generate(request(1, 1));

        assertThat(r.status()).isEqualTo("pass");
        assertThat(r.attempts()).hasSize(1);
        assertThat(r.questions()).hasSize(1);
        GenerateResult.GeneratedQuestion q = r.questions().get(0);
        assertThat(q.o().get(q.a())).isEqualTo(TestItems.STACK_OPTIONS[1]);
        assertThat(q.lv()).isEqualTo(1);
        assertThat(q.shuffleMode()).isEqualTo("random");
        assertThat(r.exampleIds()).hasSize(2);

        ModelClient.Request sent = model.requests.get(0);
        assertThat(sent.messages()).hasSize(1);
        assertThat(sent.messages().get(0).role()).isEqualTo("user");
        assertThat(sent.system()).contains("출제 위원").doesNotContain("{{");
        assertThat(sent.maxTokens()).isEqualTo(2000);
    }

    @Test
    void 심화만_상위_모델을_쓰고_재생성해도_같은_등급을_쓴다() {
        Scripted basic = new Scripted();
        service(basic).generate(request(2, 1));
        assertThat(basic.requests).isNotEmpty().allMatch(r -> r.tier() == ModelClient.Tier.BASIC);

        Scripted advanced = new Scripted();
        service(advanced).generate(request(3, 1));
        assertThat(advanced.requests).hasSize(2).allMatch(r -> r.tier() == ModelClient.Tier.ADVANCED);
    }

    @Test
    void 앞뒤에_설명이나_코드_울타리가_붙어도_JSON을_읽는다() {
        String wrapped = "물론입니다!\n```json\n" + TestItems.reply(TestItems.item(1)) + "\n```\n도움이 되길 바랍니다.";
        GenerateResult r = service(new Scripted(wrapped)).generate(request(1, 1));
        assertThat(r.status()).isEqualTo("pass");
    }

    @Test
    void 처음_실패하면_문제점을_알려_주고_한_번_더_생성해_pass_after_retry() {
        Scripted model = new Scripted(TestItems.reply(broken()), TestItems.reply(TestItems.item(1)));
        GenerateResult r = service(model).generate(request(1, 1));

        assertThat(r.status()).isEqualTo("pass-after-retry");
        assertThat(r.attempts()).hasSize(2);
        assertThat(r.attempts().get(0).errors()).contains("보기가 4개가 아님");
        assertThat(r.attempts().get(1).errors()).isEmpty();

        List<ModelClient.Turn> second = model.requests.get(1).messages();
        assertThat(second).hasSize(3);
        assertThat(second.get(1).role()).isEqualTo("assistant");
        assertThat(second.get(1).text()).isEqualTo(TestItems.reply(broken()));
        assertThat(second.get(2).role()).isEqualTo("user");
        assertThat(second.get(2).text()).contains("방금 응답에 다음 문제가 있다.", "- 1번째 문항: 보기가 4개가 아님", "questions 에 1개를 담아");
    }

    @Test
    void 두_번_다_실패하면_fail_이고_문항이_없다() {
        GenerateResult r = service(new Scripted(TestItems.reply(broken()), TestItems.reply(broken()))).generate(request(1, 1));
        assertThat(r.status()).isEqualTo("fail");
        assertThat(r.questions()).isEmpty();
        assertThat(r.attempts()).hasSize(2);
    }

    @Test
    void JSON이_아닌_응답과_빈_응답도_실패로_처리한다() {
        Scripted model = new Scripted("죄송합니다. 만들 수 없습니다.", "");
        GenerateResult r = service(model).generate(request(1, 1));
        assertThat(r.status()).isEqualTo("fail");
        assertThat(r.attempts().get(0).errors()).contains("JSON 파싱 실패 (JSON 하나만 출력해야 함)");
        // 재생성 요청에는 첫 응답 글이 assistant 턴으로 들어간다
        assertThat(model.requests.get(1).messages().get(1).text()).isEqualTo("죄송합니다. 만들 수 없습니다.");
    }

    @Test
    void 여러_문항_중_통과한_것은_두고_모자란_만큼만_다시_요청한다() {
        ObjectNode good = TestItems.item(1);
        ObjectNode other = TestItems.item(1, "큐의 특징으로 알맞은 것은?", TestItems.TAX_OPTIONS, 0);
        Scripted model = new Scripted(TestItems.reply(good, broken()), TestItems.reply(other));
        GenerateResult r = service(model).generate(request(1, 2));

        assertThat(r.status()).isEqualTo("pass-after-retry");
        assertThat(r.questions()).hasSize(2);
        assertThat(r.questions().get(0).q()).isEqualTo("스택의 정의로 가장 알맞은 것은?");
        assertThat(r.attempts().get(0).errors()).containsExactly("2번째: 보기가 4개가 아님");

        String feedback = model.requests.get(1).messages().get(2).text();
        assertThat(feedback).contains("- 2번째 문항: 보기가 4개가 아님", "[이미 낸 문항] 스택의 정의로 가장 알맞은 것은?", "questions 에 1개를 담아");
        assertThat(model.requests.get(0).maxTokens()).isEqualTo(4000);
    }

    @Test
    void 같은_응답_안의_거의_같은_문항은_중복으로_걸러낸다() {
        ObjectNode a = TestItems.item(1);
        ObjectNode copy = TestItems.item(1, "스택의 정의로 가장 알맞은 것은?", TestItems.STACK_OPTIONS, 1);
        Scripted model = new Scripted(TestItems.reply(a, copy), TestItems.reply(TestItems.item(1, "세금의 효과는?", TestItems.TAX_OPTIONS, 2)));
        GenerateResult r = service(model).generate(request(1, 2));

        assertThat(r.attempts().get(0).errors()).anyMatch(e -> e.startsWith("2번째: 다른 문항과 지문 · 보기가 너무 비슷함"));
        assertThat(r.status()).isEqualTo("pass-after-retry");
    }

    @Test
    void 이미_낸_문항과_겹치면_걸러낸다() {
        GenerateRequest req = new GenerateRequest(Persona.UNIVERSITY, "자료구조", "스택과 큐", null, new ConceptRef("c1", "스택", "LIFO"), null, 1, 1,
                List.of("스택의 정의로 가장 알맞은 것은?"), null, null);
        Scripted model = new Scripted(TestItems.reply(TestItems.item(1)), TestItems.reply(TestItems.item(1, "큐의 특징으로 알맞은 것은?", TestItems.TAX_OPTIONS, 0)));
        GenerateResult r = service(model).generate(req);

        assertThat(r.attempts().get(0).errors()).anyMatch(e -> e.contains("너무 비슷함"));
        assertThat(r.status()).isEqualTo("pass-after-retry");
        assertThat(r.questions().get(0).q()).isEqualTo("큐의 특징으로 알맞은 것은?");
        assertThat(model.requests.get(0).messages().get(0).text()).contains("[이미 낸 문항] 스택의 정의로 가장 알맞은 것은?");
    }

    @Test
    void 요청한_단계와_다른_lv_와_단계_기준_위반은_걸러낸다() {
        ObjectNode wrongLv = TestItems.item(1);
        wrongLv.put("lv", 2);
        GenerateResult r = service(new Scripted(TestItems.reply(wrongLv), TestItems.reply(wrongLv))).generate(request(1, 1));
        assertThat(r.attempts().get(0).errors()).contains("lv 가 2 (요청은 1)");

        ObjectNode shallow = TestItems.item(1);   // 1단계 모양(풀이 단계 0개)으로 3단계를 요청
        GenerateResult r3 = service(new Scripted(TestItems.reply(shallow), TestItems.reply(shallow))).generate(request(3, 1));
        assertThat(r3.attempts().get(0).errors()).anyMatch(e -> e.startsWith("lv 가 1")).anyMatch(e -> e.startsWith("concepts 1개")).anyMatch(e -> e.startsWith("steps 0개"));
    }

    @Test
    void 모델이_근거_부족으로_만들지_않으면_skipped_이고_다시_요청하지_않는다() {
        Scripted model = new Scripted("{\"questions\": [], \"skipped\": [{\"c\": \"c1\", \"reason\": \"자료에 근거가 없음\"}]}");
        GenerateResult r = service(model).generate(request(1, 1));

        assertThat(r.status()).isEqualTo("skipped");
        assertThat(r.skipped()).containsExactly(new GenerateResult.Skipped("c1", "자료에 근거가 없음"));
        assertThat(model.requests).hasSize(1);
    }

    @Test
    void 심화_문항은_개념_2개_이상_풀이_3단계_이상으로_통과한다() {
        GenerateResult r = service(new Scripted(TestItems.reply(TestItems.item(3)))).generate(request(3, 1));
        assertThat(r.status()).isEqualTo("pass");
        assertThat(r.questions().get(0).concepts()).hasSize(2);
        assertThat(r.questions().get(0).steps()).hasSize(3);
    }

    // ───────── 게이트 ③ 2차 풀이 (11/03 Java 에 추가)

    @Test
    void 이차_풀이에서_검증자가_같은_답을_고르면_통과하고_결과에_남는다() {
        Routed model = new Routed((user, n) -> answer(1, 1), TestItems.reply(TestItems.item(1)));
        GenerateResult r = verified(model).generate(request(1, 1));

        assertThat(r.status()).isEqualTo("pass");
        assertThat(r.questions().get(0).verify()).isEqualTo(new GenerateResult.Verify(true, 1, true, null, null));
        assertThat(model.verifyRequests).hasSize(1);
        ModelClient.Request v = model.verifyRequests.get(0);
        assertThat(v.maxTokens()).isEqualTo(600);
        assertThat(v.temperature()).isZero();
        assertThat(v.tier()).isEqualTo(ModelClient.Tier.BASIC);
        // 검증자에게는 지문 · 보기만 — 정답 · 해설 · 근거 · 오개념은 보내지 않는다
        String user = v.messages().get(0).text();
        assertThat(user).contains("{\"q\":\"스택의 정의로 가장 알맞은 것은?\",\"o\":[", "[3단계 - 심화·응용]", "[판정 순서]")
                .doesNotContain("해설입니다.", "\"a\"", "오개념 0", "<자료>", "{{");
    }

    @Test
    void 이차_풀이에서_정답이_다르거나_둘로_보이면_그_문항만_다시_만든다() {
        ObjectNode other = TestItems.item(1, "큐의 특징으로 알맞은 것은?", TestItems.TAX_OPTIONS, 0);
        Routed model = new Routed((user, n) -> n == 1 ? answer(2, 1) : answer(0, 1), TestItems.reply(TestItems.item(1)), TestItems.reply(other));
        GenerateResult r = verified(model).generate(request(1, 1));

        assertThat(r.status()).isEqualTo("pass-after-retry");
        assertThat(r.attempts().get(0).errors()).containsExactly("③ 정답 불일치 (출제 1, 검증자 2)");
        assertThat(model.genRequests.get(1).messages().get(2).text()).contains("- 1번째 문항: ③ 정답 불일치 (출제 1, 검증자 2)");

        Routed two = new Routed((user, n) -> "{\"correct\": [1, 3], \"grounded\": false, \"stage\": 1}", TestItems.reply(TestItems.item(1)), TestItems.reply(TestItems.item(1)));
        GenerateResult r2 = verified(two).generate(request(1, 1));
        assertThat(r2.status()).isEqualTo("fail");
        assertThat(r2.attempts().get(0).errors()).containsExactly("③ 정답이 2개 이상으로 보임 (검증자: 1, 3)", "③ 자료 · 원리로 설명되지 않는다고 판단함");
    }

    @Test
    void 게이트2에_걸린_문항은_검증_호출을_하지_않는다() {
        Routed model = new Routed((user, n) -> answer(1, 1), TestItems.reply(broken()), TestItems.reply(TestItems.item(1)));
        GenerateResult r = verified(model).generate(request(1, 1));
        assertThat(r.status()).isEqualTo("pass-after-retry");
        assertThat(model.verifyRequests).hasSize(1);
    }

    @Test
    void 단계_판정이_다르면_기록만_하고_통과시킨다() {
        GenerateResult r = verified(new Routed((user, n) -> answer(1, 3), TestItems.reply(TestItems.item(1)))).generate(request(1, 1));
        assertThat(r.status()).isEqualTo("pass");
        assertThat(r.questions().get(0).verify().stage()).isEqualTo(3);
        assertThat(r.questions().get(0).verify().stageOk()).isFalse();
    }

    @Test
    void 검증_응답이_두_번_다_깨지면_검증하지_못한_채_통과시키고_표시한다() {
        Routed model = new Routed((user, n) -> "죄송합니다", TestItems.reply(TestItems.item(1)));
        GenerateResult r = verified(model).generate(request(1, 1));
        assertThat(r.status()).isEqualTo("pass");
        assertThat(model.verifyRequests).hasSize(2);
        assertThat(r.questions().get(0).verify()).isEqualTo(new GenerateResult.Verify(false, null, null, null, "검증 응답 형식 오류 — 검증하지 못함"));
    }

    @Test
    void 여러_문항은_병렬로_검증하고_문항마다_결과가_맞게_붙는다() {
        ObjectNode a = TestItems.item(2, "스택의 정의로 가장 알맞은 것은?", TestItems.STACK_OPTIONS, 1);
        ObjectNode b = TestItems.item(2, "세금이 오를 때 일어나는 일로 알맞은 것은?", TestItems.TAX_OPTIONS, 2);
        ObjectNode c = TestItems.item(2, "큐의 특징으로 알맞은 것은?", new String[] {"양쪽 끝으로 넣는다", "먼저 넣은 것이 먼저 나온다", "가운데부터 꺼낸다", "정렬해서 넣는다"}, 1);
        // 세금 문항만 단계를 3으로 판정한다 — 결과가 문항과 엇갈리지 않는지 본다
        Routed model = new Routed((user, n) -> user.contains("세금") ? answer(2, 3) : answer(1, 2), TestItems.reply(a, b, c));
        GenerateResult r = verified(model).generate(request(2, 3));

        assertThat(r.status()).isEqualTo("pass");
        assertThat(model.verifyRequests).hasSize(3);
        assertThat(r.questions()).extracting(q -> q.q().contains("세금") ? q.verify().stage() == 3 : q.verify().stage() == 2).containsOnly(true);
    }

    @Test
    void 이차_풀이_중_모델_호출이_실패하면_감싸지_않고_그대로_올린다() {
        ObjectNode b = TestItems.item(1, "큐의 특징으로 알맞은 것은?", TestItems.TAX_OPTIONS, 0);
        ModelClient failing = new Routed((user, n) -> {
            throw new ModelCallException("throttled", null);
        }, TestItems.reply(TestItems.item(1), b));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> verified(failing).generate(request(1, 2))).isInstanceOf(ModelCallException.class);
    }

    @Test
    void 코드_문항은_결과에_코드가_있고_검증과_재생성_요청에도_코드가_들어간다() {
        ObjectNode code = TestItems.item(2, "다음 C 코드의 출력 결과는?", new String[] {"1", "2", "3", "4"}, 1);
        code.put("code", "int a[] = {1, 2, 3};\nprintf(\"%d\", a[1]);");
        code.put("shuffle", false);
        Routed model = new Routed((user, n) -> user.contains("세금") ? answer(2, 2) : answer(1, 2),
                TestItems.reply(code, broken()), TestItems.reply(TestItems.item(2, "세금의 효과는?", TestItems.TAX_OPTIONS, 2)));
        GenerateResult r = verified(model).generate(request(2, 2));

        assertThat(r.questions().get(0).code()).isEqualTo("int a[] = {1, 2, 3};\nprintf(\"%d\", a[1]);");
        assertThat(r.questions().get(1).code()).isNull();
        assertThat(model.verifyRequests.get(0).messages().get(0).text()).contains("\"code\":\"int a[] = {1, 2, 3};\\nprintf(\\\"%d\\\", a[1]);\"");
        assertThat(model.genRequests.get(1).messages().get(2).text()).contains("[이미 낸 문항] 다음 C 코드의 출력 결과는? int a[] = {1, 2, 3}; printf(\"%d\", a[1]);");
    }

    @Test
    void 이미_낸_코드_문항은_지문이_같아도_코드가_다르면_겹치지_않는다() {
        ObjectNode code = TestItems.item(2, "다음 C 코드의 출력 결과는?", new String[] {"1", "2", "3", "4"}, 1);
        code.put("code", "int a[] = {1, 2, 3};\nprintf(\"%d\", a[1]);");
        GenerateRequest req = new GenerateRequest(Persona.UNIVERSITY, "자료구조", "스택과 큐", null, new ConceptRef("c1", "스택", "LIFO"), null, 2, 1,
                List.of("다음 C 코드의 출력 결과는? int x = 7; printf(\"%d\", x % 4);"), null, null);
        GenerateResult r = service(new Scripted(TestItems.reply(code))).generate(req);
        assertThat(r.status()).isEqualTo("pass");

        GenerateRequest same = new GenerateRequest(Persona.UNIVERSITY, "자료구조", "스택과 큐", null, new ConceptRef("c1", "스택", "LIFO"), null, 2, 1,
                List.of("다음 C 코드의 출력 결과는? int a[] = {1, 2, 3}; printf(\"%d\", a[1]);"), null, null);
        GenerateResult dup = service(new Scripted(TestItems.reply(code), TestItems.reply(code))).generate(same);
        assertThat(dup.attempts().get(0).errors()).anyMatch(e -> e.contains("너무 비슷함"));
    }

    @Test
    void 모델_호출이_예외를_던지면_그대로_올라간다() {
        ModelClient failing = request -> {
            throw new ModelCallException("throttled", null);
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(failing).generate(request(1, 1))).isInstanceOf(ModelCallException.class);
    }
}
