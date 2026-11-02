package com.example.metacheck.question;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * 예시 은행(resources/prompts/examples.json — 팀 검수 전 초안 18개)에서 프롬프트에 넣을 few-shot 예시를 고른다.
 * 고르는 규칙은 ai관련/소스/pick-example.cjs 의 pickExamples 와 같다.
 */
@Component
public class ExampleBank {

    /** 예시 하나. item 은 모델이 따라 쓸 문항 JSON. */
    public record Entry(String id, Persona persona, int lv, String subject, List<String> tags, JsonNode item) {
    }

    /** pick 의 결과. fallback 은 요청 개념과 겹치지 않는 예시가 모자라 겹치는 것으로 채웠다는 뜻. */
    public record Picked(List<Entry> entries, boolean fallback) {
    }

    private final List<Entry> entries;

    public ExampleBank() {
        try (InputStream in = new ClassPathResource("prompts/examples.json").getInputStream()) {
            JsonNode root = Json.MAPPER.readTree(in);
            List<Entry> list = new ArrayList<>();
            for (JsonNode e : root.get("entries")) {
                List<String> tags = new ArrayList<>();
                for (JsonNode t : e.get("tags")) {
                    tags.add(t.asString());
                }
                list.add(new Entry(e.get("id").asString(), Persona.valueOf(e.get("persona").asString()), e.get("lv").asInt(),
                        e.get("subject").asString(), List.copyOf(tags), e.get("item")));
            }
            this.entries = List.copyOf(list);
        } catch (IOException e) {
            throw new UncheckedIOException("예시 은행을 읽지 못했습니다.", e);
        }
    }

    /**
     * ① 요청 개념 · 관련 개념과 태그가 겹치는 예시는 뺀다 ② 대학생은 같은 과목 예시를 뒤로 미룬다
     * ③ 남은 것을 seed 로 돌려 쓴다 ④ 겹치지 않는 예시가 모자라면 겹치는 것으로 채우되 fallback 표시.
     */
    public Picked pick(Persona persona, int lv, List<String> conceptNames, String subject, int seed, int n) {
        List<Entry> cell = entries.stream().filter(e -> e.persona() == persona && e.lv() == lv).toList();
        List<Entry> ok = cell.stream().filter(e -> !collides(e, conceptNames)).toList();
        List<List<Entry>> tiers = new ArrayList<>();
        if (persona == Persona.UNIVERSITY) {
            tiers.add(ok.stream().filter(e -> !e.subject().equals(subject)).toList());
            tiers.add(ok.stream().filter(e -> e.subject().equals(subject)).toList());
        } else {
            tiers.add(ok);
        }
        tiers.add(cell.stream().filter(e -> !ok.contains(e)).toList());

        List<Entry> picked = new ArrayList<>();
        for (List<Entry> tier : tiers) {
            for (int i = 0; i < tier.size(); i++) {
                Entry e = tier.get((Math.abs(seed) + i) % tier.size());
                if (picked.size() < n && !picked.contains(e)) {
                    picked.add(e);
                }
            }
        }
        boolean fallback = picked.stream().anyMatch(e -> !ok.contains(e));
        return new Picked(List.copyOf(picked), fallback);
    }

    private static boolean collides(Entry entry, List<String> names) {
        List<String> req = names.stream().map(ExampleBank::norm).filter(s -> !s.isEmpty()).toList();
        for (String tag : entry.tags()) {
            String n = norm(tag);
            for (String r : req) {
                if (r.contains(n) || n.contains(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String norm(String s) {
        return (s == null ? "" : s).replaceAll("[\\s·()\\-—,.]", "").toLowerCase();
    }
}
