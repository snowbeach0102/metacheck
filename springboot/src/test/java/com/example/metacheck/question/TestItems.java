package com.example.metacheck.question;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 테스트용 문항 JSON 만들기. 기본값은 게이트 ① · ②를 모두 통과한다. */
final class TestItems {

    static final String[] STACK_OPTIONS = {
        "먼저 들어온 자료가 먼저 나온다", "나중에 들어온 자료가 먼저 나온다", "가운데 자료부터 차례로 나온다", "크기가 작은 자료부터 나온다"};
    static final String[] TAX_OPTIONS = {
        "세금이 오르면 소비가 늘어난다", "세금이 오르면 공급이 늘어난다", "세금이 오르면 물가가 내려간다", "세금이 올라도 변화가 없다"};

    private TestItems() {
    }

    static ObjectNode item(int lv) {
        return item(lv, "스택의 정의로 가장 알맞은 것은?", STACK_OPTIONS, 1);
    }

    static ObjectNode item(int lv, String q, String[] options, int answer) {
        ObjectNode it = Json.MAPPER.createObjectNode();
        it.put("c", "c1");
        it.put("lv", lv);
        ArrayNode concepts = it.putArray("concepts");
        for (int i = 0; i < (lv == 3 ? 2 : 1); i++) {
            concepts.add("개념" + i);
        }
        ArrayNode steps = it.putArray("steps");
        for (int i = 0; i < (lv == 1 ? 0 : lv == 2 ? 1 : 3); i++) {
            steps.add("개념을 적용한 추론 단계 " + i);
        }
        it.put("q", q);
        ArrayNode o = it.putArray("o");
        for (String s : options) {
            o.add(s);
        }
        it.put("a", answer);
        it.put("shuffle", true);
        ObjectNode d = it.putObject("distractors");
        for (int i = 0; i < 4; i++) {
            if (i != answer) {
                d.put(String.valueOf(i), "오개념 " + i);
            }
        }
        it.put("x", "해설입니다.");
        it.put("ev", "근거");
        return it;
    }

    /** {"questions":[...], "skipped":[]} 모양의 모델 응답 글. */
    static String reply(ObjectNode... items) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode qs = root.putArray("questions");
        for (ObjectNode it : items) {
            qs.add(it);
        }
        root.putArray("skipped");
        return root.toString();
    }
}
