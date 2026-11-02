package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ObjectNode;

class QuestionGatesTest {

    @Test
    void 정상_문항은_모든_단계에서_통과한다() {
        for (int lv = 1; lv <= 3; lv++) {
            ObjectNode it = TestItems.item(lv);
            assertThat(QuestionGates.gate1(it)).as("gate1 lv" + lv).isEmpty();
            assertThat(QuestionGates.gate2(it, lv)).as("gate2 lv" + lv).isEmpty();
        }
    }

    @Test
    void 게이트1_보기가_4개가_아니면_걸린다() {
        ObjectNode it = TestItems.item(1);
        ((tools.jackson.databind.node.ArrayNode) it.get("o")).remove(3);
        assertThat(QuestionGates.gate1(it)).contains("보기가 4개가 아님");
    }

    @Test
    void 게이트1_보기_중복_정답번호_범위_필드_누락을_잡는다() {
        ObjectNode dup = TestItems.item(1, "질문?", new String[] {"같다", "같다", "다르다", "또 다르다"}, 2);
        assertThat(QuestionGates.gate1(dup)).contains("보기 중복");

        ObjectNode badA = TestItems.item(1);
        badA.put("a", 4);
        assertThat(QuestionGates.gate1(badA)).contains("a 가 0~3 정수가 아님");

        ObjectNode noX = TestItems.item(1);
        noX.remove("x");
        assertThat(QuestionGates.gate1(noX)).contains("x 없음");

        ObjectNode nullQ = TestItems.item(1);
        nullQ.putNull("q");
        assertThat(QuestionGates.gate1(nullQ)).contains("q 없음");

        ObjectNode badShuffle = TestItems.item(1);
        badShuffle.put("shuffle", "true");
        assertThat(QuestionGates.gate1(badShuffle)).contains("shuffle 이 true/false 가 아님");
    }

    @Test
    void 게이트1_distractors_키는_정답을_뺀_3개여야_한다() {
        ObjectNode it = TestItems.item(1);
        ((ObjectNode) it.get("distractors")).remove("0");
        assertThat(QuestionGates.gate1(it)).contains("distractors 키가 정답을 뺀 3개와 다름");

        ObjectNode none = TestItems.item(1);
        none.remove("distractors");
        assertThat(QuestionGates.gate1(none)).contains("distractors 없음");
    }

    @Test
    void 게이트2_단계별_개념_풀이단계_개수를_본다() {
        ObjectNode lv1 = TestItems.item(1);
        lv1.putArray("concepts").add("a").add("b");
        assertThat(QuestionGates.gate2(lv1, 1)).contains("concepts 2개 (기준 1~1개)");

        ObjectNode lv3 = TestItems.item(3);
        lv3.putArray("steps").add("하나").add("둘");
        assertThat(QuestionGates.gate2(lv3, 3)).contains("steps 2개 (기준 3개 이상)");
    }

    @Test
    void 게이트2_지문_문장_수_상한은_기초_2_실전_심화_3() {
        String three = "첫째 문장이다. 둘째 문장이다. 셋째 문장인가?";
        assertThat(QuestionGates.gate2(TestItems.item(1, three, TestItems.STACK_OPTIONS, 1), 1)).contains("지문 3문장 (최대 2)");
        assertThat(QuestionGates.gate2(TestItems.item(2, three, TestItems.STACK_OPTIONS, 1), 2)).noneMatch(s -> s.startsWith("지문"));
    }

    @Test
    void 문장_수_세기는_소수점과_번호_뒤_마침표를_문장_끝으로_보지_않는다() {
        assertThat(QuestionGates.sentences("가격이 3.5% 오르면 수요량은 얼마나 변하는가?")).isEqualTo(1);
        assertThat(QuestionGates.sentences("A이다. B이다.")).isEqualTo(2);
        assertThat(QuestionGates.sentences("")).isZero();
    }

    @Test
    void 게이트2_금지_표현을_지문_보기_해설_오답설명_전체에서_찾는다() {
        ObjectNode inOption = TestItems.item(1);
        inOption.putArray("o").add("위의 모두 옳다").add("나는 다르다").add("너도 다르다").add("우리는 다르다");
        assertThat(QuestionGates.gate2(inOption, 1)).contains("금지 표현(보기 번호 · 보기 참조)");

        ObjectNode inExplanation = TestItems.item(1);
        inExplanation.put("x", "1번과 2번은 틀리고 3번이 맞다.");
        assertThat(QuestionGates.gate2(inExplanation, 1)).contains("금지 표현(보기 번호 · 보기 참조)");

        ObjectNode circled = TestItems.item(1);
        ((ObjectNode) circled.get("distractors")).put("0", "②와 혼동");
        assertThat(QuestionGates.gate2(circled, 1)).contains("금지 표현(보기 번호 · 보기 참조)");
    }

    @Test
    void 번째_같은_일반_표현은_금지_표현이_아니다() {
        ObjectNode it = TestItems.item(1);
        it.put("x", "2번째 원소가 먼저 나오고 3개월 뒤에 1번 더 쓴다.");
        // "1번 더"는 'N번' 이라 걸리지만 "2번째"는 걸리지 않는다 — 걸리는 것은 아래 한 건뿐이어야 한다
        assertThat(QuestionGates.gate2(it, 1)).containsExactly("금지 표현(보기 번호 · 보기 참조)");
        it.put("x", "2번째 원소가 먼저 나온다.");
        assertThat(QuestionGates.gate2(it, 1)).isEmpty();
    }

    @Test
    void 게이트2_정답_보기가_가장_길고_오답_평균의_1_25배를_넘으면_걸린다() {
        String[] biased = {
            "짧은 오답입니다 하나", "짧은 오답입니다 둘", "짧은 오답입니다 셋", "이 보기는 정답이며 다른 보기보다 훨씬 길고 자세하게 쓰여 있습니다"};
        assertThat(QuestionGates.gate2(TestItems.item(1, "질문?", biased, 3), 1))
                .anyMatch(s -> s.startsWith("정답 보기가 가장 길다 (오답 평균의 "));
    }

    @Test
    void 게이트2_짧은_숫자_보기는_길이_검사에서_뺀다() {
        String[] numbers = {"1", "2", "3", "1234567"};
        assertThat(QuestionGates.gate2(TestItems.item(1, "질문?", numbers, 3), 1)).isEmpty();
    }

    @Test
    void 유사도_같은_글은_1_전혀_다른_글은_낮고_살짝_고친_글은_기준_이상() {
        String a = "스택의 정의로 가장 알맞은 것은? 나중에 들어온 자료가 먼저 나온다";
        assertThat(QuestionGates.similar(a, a)).isEqualTo(1.0);
        assertThat(QuestionGates.similar(a, "수요의 가격탄력성이 1보다 큰 재화의 총수입 변화")).isLessThan(0.3);
        assertThat(QuestionGates.similar(a, "스택의 정의로 가장 알맞은 것은? 나중에 들어온 자료가 먼저 나오는 구조")).isGreaterThanOrEqualTo(QuestionGates.DUP);
        assertThat(QuestionGates.similar("", a)).isZero();
    }
}
