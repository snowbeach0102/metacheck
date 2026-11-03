package com.example.metacheck.question;

import java.util.ArrayList;
import java.util.List;

/**
 * 문제 생성 요청 (API 본문). 값이 비었거나 범위를 벗어나면 {@link #toInput()} 이 IllegalArgumentException 을 던진다.
 *
 * @param persona           출제 역할. 없으면 대학생
 * @param examName          준비 중인 시험 이름. 11/03 범위 축소 동안은 쓰지 않는다 — 수험생은 서버가 정보처리산업기사로 고정 (API 모양은 그대로 둠)
 * @param related           같은 진단의 나머지 개념. 3단계(심화)는 1개 이상 필요
 * @param previousQuestions 이미 낸 문항의 지문 (다시 생성 · 유사 문항에서 겹치지 않게)
 * @param material          PDF 단원 발췌 (없으면 생략)
 * @param userRequest       말투 · 예시 같은 스타일 힌트 (100자)
 */
public record GenerateRequest(
        Persona persona,
        String subject,
        String topic,
        String examName,
        ConceptRef concept,
        List<ConceptRef> related,
        Integer level,
        Integer count,
        List<String> previousQuestions,
        String material,
        String userRequest) {

    /** 한 호출의 문항 수 상한 (사양서: 호출당 5문항 이하 권장). */
    public static final int MAX_COUNT = 5;
    private static final int MAX_RELATED = 5;
    private static final int MAX_PREVIOUS = 10;

    PromptInput toInput() {
        Persona p = persona == null ? Persona.UNIVERSITY : persona;
        String sub = InputCleaner.cleanLine(subject, InputCleaner.SUBJECT_MAX);
        String top = InputCleaner.cleanLine(topic, InputCleaner.TOPIC_MAX);
        if (sub.isEmpty()) {
            throw new IllegalArgumentException("subject(과목)가 비어 있습니다.");
        }
        if (top.isEmpty()) {
            throw new IllegalArgumentException("topic(단원)이 비어 있습니다.");
        }
        if (concept == null || InputCleaner.cleanLine(concept.name(), InputCleaner.CONCEPT_NAME_MAX).isEmpty()) {
            throw new IllegalArgumentException("concept.name(출제할 개념)이 비어 있습니다.");
        }
        if (level == null || level < 1 || level > 3) {
            throw new IllegalArgumentException("level(단계)은 1~3 이어야 합니다.");
        }
        int n = count == null ? 1 : count;
        if (n < 1 || n > MAX_COUNT) {
            throw new IllegalArgumentException("count(문항 수)는 1~" + MAX_COUNT + " 이어야 합니다.");
        }

        String conceptId = InputCleaner.cleanLine(concept.id(), 20);
        ConceptRef main = new ConceptRef(
                conceptId.isEmpty() ? "c1" : conceptId,
                InputCleaner.cleanLine(concept.name(), InputCleaner.CONCEPT_NAME_MAX),
                InputCleaner.cleanLine(concept.desc(), InputCleaner.CONCEPT_DESC_MAX));

        List<ConceptRef> rel = new ArrayList<>();
        for (ConceptRef r : related == null ? List.<ConceptRef>of() : related) {
            if (r == null || rel.size() >= MAX_RELATED) {
                continue;
            }
            String name = InputCleaner.cleanLine(r.name(), InputCleaner.CONCEPT_NAME_MAX);
            if (!name.isEmpty()) {
                rel.add(new ConceptRef(null, name, InputCleaner.cleanLine(r.desc(), InputCleaner.CONCEPT_DESC_MAX)));
            }
        }
        if (level == 3 && rel.isEmpty()) {
            throw new IllegalArgumentException("3단계(심화)는 연결할 related(관련 개념)가 1개 이상 필요합니다.");
        }

        List<String> prev = new ArrayList<>();
        for (String q : previousQuestions == null ? List.<String>of() : previousQuestions) {
            String t = InputCleaner.cleanLine(q, InputCleaner.PREVIOUS_MAX);
            if (!t.isEmpty() && prev.size() < MAX_PREVIOUS) {
                prev.add(t);
            }
        }

        String exam = p == Persona.CERT_EXAM ? PromptTemplates.EXAM_NAME : "";
        return new PromptInput(p, sub, top, exam, main, List.copyOf(rel), level, n, List.copyOf(prev),
                InputCleaner.cleanMaterial(material), InputCleaner.cleanUserRequest(userRequest));
    }
}
