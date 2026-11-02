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

    private static QuestionGenerationService service(ModelClient client) {
        return new QuestionGenerationService(new PromptAssembler(new PromptTemplates(), new ExampleBank()), client, () -> 0, () -> new Random(1));
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

    @Test
    void 모델_호출이_예외를_던지면_그대로_올라간다() {
        ModelClient failing = request -> {
            throw new ModelCallException("throttled", null);
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(failing).generate(request(1, 1))).isInstanceOf(ModelCallException.class);
    }
}
