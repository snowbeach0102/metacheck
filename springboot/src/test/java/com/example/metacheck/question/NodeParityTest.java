package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.JsonNode;

/**
 * Java 포팅이 Node 원본(ai관련/bedrock_12칸/run-bedrock.mjs)과 같은 판정을 내리는지 본다.
 * golden/parity.json 은 Node 원본이 예시 은행 문항 6개를 14가지로 망가뜨린 변형(84건)에 내린 gate1 · gate2 결과,
 * 보기 고정 순서 감지 결과(23건), 문항 쌍 유사도(23쌍)를 저장한 것이다.
 * Node 쪽 기준을 바꾸면(예: 길이 편향 배수) 이 파일을 다시 만들어야 한다 — 그때 이 테스트가 달라진 판정을 보여 준다.
 */
class NodeParityTest {

    private static JsonNode golden;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = new ClassPathResource("golden/parity.json").getInputStream()) {
            golden = Json.MAPPER.readTree(in);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.asString()));
        return out;
    }

    @Test
    void 게이트1_게이트2가_Node_원본과_같은_오류를_낸다() {
        assertThat(golden.get("gates").size()).isEqualTo(84);
        int checkedGate2 = 0;
        for (JsonNode g : golden.get("gates")) {
            String where = g.get("id").asString() + " / " + g.get("mutation").asString();
            JsonNode item = g.get("item");

            List<String> g1 = QuestionGates.gate1(item);
            assertThat(g1).as(where + " gate1").isEqualTo(strings(g.get("gate1")));

            if (g1.isEmpty()) {
                for (int lv = 1; lv <= 3; lv++) {
                    assertThat(QuestionGates.gate2(item, lv)).as(where + " gate2 lv" + lv).isEqualTo(strings(g.get("gate2").get(lv - 1)));
                    checkedGate2++;
                }
            }
        }
        assertThat(checkedGate2).as("gate2 까지 비교한 건수").isGreaterThan(100);
    }

    @Test
    void 변형하지_않은_예시_문항은_자기_단계에서_두_게이트를_모두_통과한다() {
        int n = 0;
        for (JsonNode g : golden.get("gates")) {
            if (g.get("mutation").asString().equals("none")) {
                JsonNode item = g.get("item");
                assertThat(QuestionGates.gate1(item)).as(g.get("id").asString()).isEmpty();
                assertThat(QuestionGates.gate2(item, item.get("lv").asInt())).as(g.get("id").asString()).isEmpty();
                n++;
            }
        }
        assertThat(n).isEqualTo(6);
    }

    @Test
    void 보기_고정_순서_감지가_Node_원본과_같다() {
        int detected = 0;
        for (JsonNode f : golden.get("fixed")) {
            List<String> options = strings(f.get("options"));
            OptionShuffler.FixedOrder got = OptionShuffler.detectFixedOrder(options);
            JsonNode expected = f.get("result");
            if (expected.isNull()) {
                assertThat(got).as(options.toString()).isNull();
            } else {
                assertThat(got).as(options.toString()).isNotNull();
                assertThat(got.kind()).as(options.toString()).isEqualTo(expected.get("kind").asString());
                int[] order = new int[expected.get("order").size()];
                for (int i = 0; i < order.length; i++) {
                    order[i] = expected.get("order").get(i).asInt();
                }
                assertThat(got.order()).as(options.toString()).containsExactly(order);
                detected++;
            }
        }
        assertThat(detected).as("고정 순서로 감지된 건수").isGreaterThan(8);
    }

    @Test
    void 유사도_값이_Node_원본과_같다() {
        for (JsonNode s : golden.get("sims")) {
            assertThat(QuestionGates.similar(s.get("a").asString(), s.get("b").asString())).isCloseTo(s.get("value").asDouble(), within(1e-9));
        }
    }
}
