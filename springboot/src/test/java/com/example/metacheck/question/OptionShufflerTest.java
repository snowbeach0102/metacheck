package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ObjectNode;

class OptionShufflerTest {

    @Test
    void 조합형_보기는_짧은_조합부터_글자_순으로_정렬한다() {
        OptionShuffler.FixedOrder f = OptionShuffler.detectFixedOrder(List.of("ㄱ, ㄷ", "ㄱ", "ㄴ, ㄷ", "ㄱ, ㄴ"));
        assertThat(f.kind()).isEqualTo("combo");
        assertThat(f.order()).containsExactly(1, 3, 0, 2);
    }

    @Test
    void 숫자형_보기는_값_순으로_정렬한다() {
        OptionShuffler.FixedOrder f = OptionShuffler.detectFixedOrder(List.of("0.5", "2", "1.5", "0.8"));
        assertThat(f.kind()).isEqualTo("numeric");
        assertThat(f.order()).containsExactly(0, 3, 2, 1);

        OptionShuffler.FixedOrder withWords = OptionShuffler.detectFixedOrder(List.of("탄력성 0.5", "탄력성 2", "탄력성 1.5", "탄력성 0.8"));
        assertThat(withWords.order()).containsExactly(0, 3, 2, 1);
    }

    @Test
    void 쉼표_천단위_음수도_숫자로_읽는다() {
        OptionShuffler.FixedOrder f = OptionShuffler.detectFixedOrder(List.of("1,200원", "-30원", "450원", "15원"));
        assertThat(f.kind()).isEqualTo("numeric");
        assertThat(f.order()).containsExactly(1, 3, 2, 0);
    }

    @Test
    void 순서형_보기는_번호_순으로_정렬한다() {
        // 번호 뒤 글이 서로 다르면 숫자형이 아니라 순서형으로 본다
        OptionShuffler.FixedOrder f = OptionShuffler.detectFixedOrder(List.of("제3원칙: 공개", "제1원칙: 책임", "제2원칙: 동의", "제4원칙: 최소"));
        assertThat(f.kind()).isEqualTo("ordinal");
        assertThat(f.order()).containsExactly(1, 2, 0, 3);

        // 번호 말고는 글이 같으면 숫자형 규칙이 먼저 잡는다 (정렬 결과는 같다)
        OptionShuffler.FixedOrder same = OptionShuffler.detectFixedOrder(List.of("제3원칙", "제1원칙", "제2원칙", "제4원칙"));
        assertThat(same.kind()).isEqualTo("numeric");
        assertThat(same.order()).containsExactly(1, 2, 0, 3);
    }

    @Test
    void 일반_문장_보기는_고정_순서가_아니다() {
        assertThat(OptionShuffler.detectFixedOrder(List.of("수요 증가", "수요 감소", "공급 증가", "공급 감소"))).isNull();
        assertThat(OptionShuffler.detectFixedOrder(List.of("스택은 LIFO", "큐는 FIFO", "덱은 양쪽", "힙은 트리"))).isNull();
    }

    @Test
    void 섞은_뒤에도_정답_보기와_오답_설명이_짝을_유지한다() {
        for (long seed = 0; seed < 30; seed++) {
            ObjectNode it = TestItems.item(1);
            OptionShuffler.Shuffled s = OptionShuffler.shuffle(it, new Random(seed));

            assertThat(s.mode()).isEqualTo("random");
            assertThat(s.o()).containsExactlyInAnyOrder(TestItems.STACK_OPTIONS);
            assertThat(s.o().get(s.a())).isEqualTo(TestItems.STACK_OPTIONS[1]);
            assertThat(s.distractors().keySet()).doesNotContain(s.a()).hasSize(3);
            // 오답 설명 "오개념 i" 는 원래 i 번 보기에 붙어 있었다
            s.distractors().forEach((newI, text) -> {
                int oldI = Integer.parseInt(text.substring("오개념 ".length()));
                assertThat(s.o().get(newI)).isEqualTo(TestItems.STACK_OPTIONS[oldI]);
            });
        }
    }

    @Test
    void 여러_번_섞으면_정답_위치가_한곳에_몰리지_않는다() {
        Random rnd = new Random(7);
        int[] seen = new int[4];
        for (int i = 0; i < 400; i++) {
            seen[OptionShuffler.shuffle(TestItems.item(1), rnd).a()]++;
        }
        for (int count : seen) {
            assertThat(count).isBetween(60, 140);
        }
    }

    @Test
    void 모델이_shuffle_false_로_쓰면_그대로_둔다() {
        ObjectNode it = TestItems.item(1);
        it.put("shuffle", false);
        OptionShuffler.Shuffled s = OptionShuffler.shuffle(it, new Random(1));
        assertThat(s.mode()).isEqualTo("kept-model");
        assertThat(s.o()).containsExactly(TestItems.STACK_OPTIONS);
        assertThat(s.a()).isEqualTo(1);
        assertThat(s.note()).isEmpty();
    }

    @Test
    void 모델이_true_로_썼어도_숫자_보기는_서버가_정렬하고_이유를_남긴다() {
        ObjectNode it = TestItems.item(1, "탄력성은?", new String[] {"2", "0.5", "1.5", "0.8"}, 2);
        OptionShuffler.Shuffled s = OptionShuffler.shuffle(it, new Random(1));
        assertThat(s.mode()).isEqualTo("sorted-numeric");
        assertThat(s.o()).containsExactly("0.5", "0.8", "1.5", "2");
        assertThat(s.a()).isEqualTo(2);  // 정답 "1.5" 는 정렬 뒤 세 번째
        assertThat(s.note()).contains("숫자형").contains("shuffle:true");
    }
}
