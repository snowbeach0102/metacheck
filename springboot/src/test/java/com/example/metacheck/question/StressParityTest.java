package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.JsonNode;

/**
 * 모의 출제 스트레스 문항 50개(문서 저장소 ai관련/소스/stress-items.cjs — 정상 22 · 모델 실수 20 · 규칙이 못 잡는 종류 8)에
 * 실제 서버의 문항 검사(QuestionGenerationService.check)가 Node 원본과 똑같은 판정을 내는지 본다 (golden/stress.json).
 * 문항을 고치거나 규칙을 고치면 `node tools/sync-from-ai-spec.mjs` 로 골든을 다시 만든다.
 */
class StressParityTest {

    private static JsonNode golden() throws IOException {
        try (InputStream in = new ClassPathResource("golden/stress.json").getInputStream()) {
            return Json.MAPPER.readTree(in);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.asString()));
        return out;
    }

    @Test
    void 모의_출제_문항_50개에_Java_검사가_Node_와_같은_판정을_낸다() throws IOException {
        JsonNode all = golden();
        assertThat(all.size()).isEqualTo(50);
        int good = 0;
        int flawed = 0;
        int gap = 0;
        for (JsonNode e : all) {
            String id = e.get("id").asString();
            List<String> seen = new ArrayList<>();
            e.get("seen").forEach(p -> seen.add(QuestionGates.itemText(p)));

            List<String> got = QuestionGenerationService.check(e.get("item"), e.get("lv").asInt(), List.of(), seen);

            assertThat(got).as(id + " " + e.get("kind").asString()).isEqualTo(strings(e.get("errors")));
            switch (e.get("kind").asString()) {
                case "good" -> {
                    assertThat(got).as(id + ": 정상 문항이 규칙에 막힘(오탐)").isEmpty();
                    good++;
                }
                case "flawed" -> {
                    assertThat(got).as(id + ": 모델 실수를 규칙이 못 잡음").isNotEmpty();
                    flawed++;
                }
                default -> {
                    assertThat(got).as(id + ": 규칙이 못 잡는 종류(2차 풀이 · 사람 몫) — 이제 잡으면 stress-items.cjs 의 gaps 를 flawed 로 옮길 것").isEmpty();
                    gap++;
                }
            }
        }
        assertThat(good).isEqualTo(22);
        assertThat(flawed).isEqualTo(20);
        assertThat(gap).isEqualTo(8);
    }
}
