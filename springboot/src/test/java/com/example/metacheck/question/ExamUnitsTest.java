package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ExamUnitsTest {

    private final ExamUnits units = new ExamUnits();

    @Test
    void 출제기준의_3과목_주요항목_8개_세부항목_22개를_그대로_담는다() {
        assertThat(units.exam()).isEqualTo("정보처리산업기사");
        assertThat(units.subjects()).extracting(ExamUnits.Subject::name).containsExactly("정보시스템 기반 기술", "프로그래밍언어 활용", "데이터베이스 활용");
        assertThat(units.subjects()).extracting(s -> s.items().size()).containsExactly(3, 2, 3);
        assertThat(units.subjects()).extracting(s -> s.items().stream().mapToInt(i -> i.units().size()).sum()).containsExactly(8, 7, 7);
        assertThat(units.units()).hasSize(22);
    }

    @Test
    void 단원표와_프롬프트의_출제_범위가_같은_이름을_쓴다() {
        // 출제 범위 글(exam-scope.txt)과 단원표가 따로 놀면 AI 가 받는 범위와 앱 · 서버의 단원이 어긋난다
        String scope = new PromptTemplates().examScope();
        for (ExamUnits.Subject s : units.subjects()) {
            assertThat(scope).contains(s.name());
            for (ExamUnits.Item item : s.items()) {
                assertThat(scope).contains(item.name());
                item.units().forEach(u -> assertThat(scope).as(u.id()).contains(u.name()));
            }
        }
    }

    @Test
    void 단원마다_개념_3개이고_이름은_20자_설명은_80자_이내다() {
        for (ExamUnits.Unit u : units.units()) {
            assertThat(u.concepts()).as(u.id()).extracting(ConceptRef::id).containsExactly("c1", "c2", "c3");
            u.concepts().forEach(c -> {
                assertThat(c.name().length()).as(u.id() + " " + c.name()).isBetween(1, 20);
                assertThat(c.desc().length()).as(u.id() + " " + c.name()).isBetween(1, 80);
            });
            assertThat(u.subject()).isNotBlank();
            assertThat(u.item()).isNotBlank();
        }
    }

    @Test
    void 앱에_문제은행이_있는_단원은_3개이고_id로_찾는다() {
        List<String> banked = units.units().stream().filter(u -> u.bankId() != null).map(u -> u.id() + "=" + u.bankId()).toList();
        assertThat(banked).containsExactly("sys-os=os", "lang-feature=lang", "db-basic=db");
        assertThat(units.find("db-sql-adv")).get().extracting(ExamUnits.Unit::name).isEqualTo("고급 SQL 작성");
        assertThat(units.find("없는-단원")).isEmpty();
        assertThat(units.find(null)).isEmpty();
    }
}
