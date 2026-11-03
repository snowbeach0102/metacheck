package com.example.metacheck.question;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * 정보처리산업기사 단원표(resources/prompts/exam-units.json) — 과목 → 주요항목 → 세부항목(단원 22개)과 단원마다 개념 3개.
 * 원본은 ai관련/소스/exam-units.json 이고 tools/sync-from-ai-spec.mjs 가 복사한다. 개념은 큐넷 세세항목 확인 전 초안이다.
 */
@Component
public class ExamUnits {

    /** 진단 한 번이 다루는 개념 수 — 앱의 문제은행 한 단원과 같다. */
    static final int CONCEPTS_PER_UNIT = 3;

    /** 단원(세부항목) 하나. bankId 는 앱에 손으로 쓴 문제은행이 있는 단원만 (없으면 null). */
    public record Unit(String id, String subject, String item, String name, String bankId, List<ConceptRef> concepts) {
    }

    public record Item(String name, List<Unit> units) {
    }

    public record Subject(String id, String name, List<Item> items) {
    }

    private final String exam;
    private final List<Subject> subjects;
    private final Map<String, Unit> byId = new LinkedHashMap<>();

    public ExamUnits() {
        try (InputStream in = new ClassPathResource("prompts/exam-units.json").getInputStream()) {
            JsonNode root = Json.MAPPER.readTree(in);
            this.exam = root.get("exam").asString();
            List<Subject> list = new ArrayList<>();
            for (JsonNode s : root.get("subjects")) {
                List<Item> items = new ArrayList<>();
                for (JsonNode it : s.get("items")) {
                    List<Unit> units = new ArrayList<>();
                    for (JsonNode u : it.get("units")) {
                        List<ConceptRef> concepts = new ArrayList<>();
                        for (JsonNode c : u.get("concepts")) {
                            concepts.add(new ConceptRef(c.get("id").asString(), c.get("name").asString(), c.get("desc").asString()));
                        }
                        JsonNode bank = u.get("bankId");
                        Unit unit = new Unit(u.get("id").asString(), s.get("name").asString(), it.get("name").asString(), u.get("name").asString(),
                                bank == null || bank.isNull() ? null : bank.asString(), List.copyOf(concepts));
                        if (unit.concepts().size() != CONCEPTS_PER_UNIT || byId.putIfAbsent(unit.id(), unit) != null) {
                            throw new IllegalStateException("단원표가 잘못됐습니다(개념 3개 · 겹치지 않는 id): " + unit.id());
                        }
                        units.add(unit);
                    }
                    items.add(new Item(it.get("name").asString(), List.copyOf(units)));
                }
                list.add(new Subject(s.get("id").asString(), s.get("name").asString(), List.copyOf(items)));
            }
            this.subjects = List.copyOf(list);
        } catch (IOException e) {
            throw new UncheckedIOException("단원표를 읽지 못했습니다.", e);
        }
    }

    public String exam() {
        return exam;
    }

    public List<Subject> subjects() {
        return subjects;
    }

    public List<Unit> units() {
        return List.copyOf(byId.values());
    }

    public Optional<Unit> find(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }
}
