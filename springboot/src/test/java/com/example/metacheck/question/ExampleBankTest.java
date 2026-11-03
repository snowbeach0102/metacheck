package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** 예시 고르기 규칙(문서 저장소 ai관련/소스/test-bank.mjs 의 2-2와 같은 사례) + 11/03에 더한 예시. DB · 스프링 없이 돈다. */
class ExampleBankTest {

    private static final String SYS = "정보시스템 기반 기술";
    private static final String LANG = "프로그래밍언어 활용";
    private static final String DB = "데이터베이스 활용";
    private static final List<String> NONE = List.of("없는 개념");

    private final ExampleBank bank = new ExampleBank();

    private List<String> ids(Persona p, int lv, List<String> concepts, String subject, int seed, int n) {
        return bank.pick(p, lv, concepts, subject, seed, n).entries().stream().map(ExampleBank.Entry::id).toList();
    }

    @Test
    void 수험생은_단원이_속한_과목의_예시를_먼저_받는다() {
        for (int seed = 0; seed < 5; seed++) {
            for (String subject : List.of(SYS, LANG, DB)) {
                ExampleBank.Entry first = bank.pick(Persona.CERT_EXAM, 2, NONE, subject, seed, 2).entries().get(0);
                assertThat(first.subject()).as("2단계 " + subject + " seed " + seed).isEqualTo(subject);
            }
        }
    }

    @Test
    void 정보시스템과_프로그래밍언어는_2단계에_같은_과목_예시가_둘씩이라_둘_다_같은_과목이다() {
        for (int seed = 0; seed < 3; seed++) {
            assertThat(ids(Persona.CERT_EXAM, 2, NONE, SYS, seed, 2)).containsExactlyInAnyOrder("C2-a", "C2-d");
            assertThat(ids(Persona.CERT_EXAM, 2, NONE, LANG, seed, 2)).containsExactlyInAnyOrder("C2-b", "C2-e");
        }
    }

    @Test
    void 네트워크_단원_요청은_네트워크_예시를_받는다() {
        // 예전에는 단원과 상관없이 "FIFO 페이지 교체 + C 포인터"를 받았다 (11/03에 고침)
        List<String> got = ids(Persona.CERT_EXAM, 2, List.of("TCP", "UDP"), SYS, 0, 2);
        assertThat(got).contains("C2-d").doesNotContain("C2-b");
    }

    @Test
    void 같은_과목_예시가_요청_개념과_겹치면_빠지고_다른_과목으로_채운다() {
        List<String> got = ids(Persona.CERT_EXAM, 2, List.of("IP 주소와 서브네팅"), SYS, 0, 2);
        assertThat(got).hasSize(2).doesNotContain("C2-d");
        assertThat(got.get(0)).isEqualTo("C2-a");
    }

    @Test
    void 과목이_안_맞으면_자유_입력_예전처럼_후보_전체를_돌려_쓴다() {
        Set<String> firsts = IntStream.range(0, 5).mapToObj(seed -> ids(Persona.CERT_EXAM, 2, NONE, "없는 과목", seed, 1).get(0)).collect(Collectors.toSet());
        assertThat(firsts).hasSize(5);
    }

    @Test
    void 대학생은_그대로_같은_과목이_뒤로_밀린다() {
        assertThat(bank.pick(Persona.UNIVERSITY, 1, NONE, "통계학", 0, 2).entries()).allMatch(e -> !e.subject().equals("통계학"));
    }

    @Test
    void 칸마다_예시가_대학생_3개_수험생_4_5_4개이고_11_03에_더한_네_영역이_들어_있다() {
        assertThat(ids(Persona.UNIVERSITY, 1, NONE, "x", 0, 99)).hasSize(3);
        assertThat(ids(Persona.UNIVERSITY, 2, NONE, "x", 0, 99)).hasSize(3);
        assertThat(ids(Persona.UNIVERSITY, 3, NONE, "x", 0, 99)).hasSize(3);
        assertThat(ids(Persona.CERT_EXAM, 1, NONE, "x", 0, 99)).hasSize(4).contains("C1-d");   // 객체지향 설계(싱글톤)
        assertThat(ids(Persona.CERT_EXAM, 2, NONE, "x", 0, 99)).hasSize(5).contains("C2-d", "C2-e");   // 네트워크(서브넷) · Python
        assertThat(ids(Persona.CERT_EXAM, 3, NONE, "x", 0, 99)).hasSize(4).contains("C3-d");   // 테스트(커버리지)
    }
}
