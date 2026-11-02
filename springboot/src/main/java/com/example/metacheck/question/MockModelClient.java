package com.example.metacheck.question;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 개발용 가짜 모델. 요청의 [개념] · [단계] · [문항 수] 줄을 읽어, 검사(게이트)를 통과하는 모양의 문항 JSON 을 돌려준다.
 * 내용은 일부러 뜻 없는 글자를 섞은 "[모의]" 문장이라 실제 문제로 오해할 일이 없다.
 * 실제 Bedrock 연결 전까지 기본값이며, 설정 metacheck.ai.client=mock (없으면 mock) 일 때만 켜진다.
 */
@Component
@ConditionalOnProperty(name = "metacheck.ai.client", havingValue = "mock", matchIfMissing = true)
public class MockModelClient implements ModelClient {

    private static final Pattern LEVEL = Pattern.compile("\\(lv (\\d)\\)");
    private static final Pattern COUNT = Pattern.compile("\\[문항 수] (\\d+)");
    private static final Pattern CONCEPT = Pattern.compile("\\[개념] (.+?)(?: — .*?)?   \\(개념 id: (.+?)\\)");
    private static final Pattern RELATED = Pattern.compile("\\[관련 개념] (.+?)(?: — |$| / )", Pattern.MULTILINE);

    @Override
    public Reply invoke(Request request) {
        String user = request.messages().get(0).text();
        int lv = find(LEVEL, user, 1);
        int count = find(COUNT, user, 1);
        Matcher cm = CONCEPT.matcher(user);
        String name = cm.find() ? cm.group(1) : "개념";
        String id = cm.group(2);
        Matcher rm = RELATED.matcher(user);
        String related = rm.find() ? rm.group(1) : "관련 개념";

        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode questions = root.putArray("questions");
        for (int i = 0; i < count; i++) {
            questions.add(item(name, related, id, lv, i));
        }
        root.putArray("skipped");
        return new Reply(root.toString(), "end_turn");
    }

    private static int find(Pattern p, String text, int fallback) {
        Matcher m = p.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    // 문항 i 마다 뜻 없는 글자 조각이 달라 서로 겹침(중복) 검사에 걸리지 않는다
    private static ObjectNode item(String name, String related, String id, int lv, int i) {
        Random r = new Random((name + "|" + lv + "|" + i).hashCode());
        int answer = i % 4;

        ObjectNode it = Json.MAPPER.createObjectNode();
        it.put("c", id);
        it.put("lv", lv);
        ArrayNode concepts = it.putArray("concepts");
        concepts.add(name);
        if (lv == 3) {
            concepts.add(related);
        }
        ArrayNode steps = it.putArray("steps");
        for (int s = 0; s < (lv == 1 ? 0 : lv == 2 ? 1 : 3); s++) {
            steps.add("[모의] 풀이 단계 " + (s + 1) + " " + gibberish(r, 8));
        }

        StringBuilder q = new StringBuilder("[모의 " + (i + 1) + "] " + name + " " + gibberish(r, 10));
        for (int s = 1; s < lv; s++) {
            q.append(". ").append(gibberish(r, 12));
        }
        q.append("?");
        it.put("q", q.toString());

        ArrayNode o = it.putArray("o");
        for (int k = 0; k < 4; k++) {
            o.add("[모의] 보기 " + (char) ('A' + k) + " " + gibberish(r, 14));
        }
        it.put("a", answer);
        it.put("shuffle", true);
        ObjectNode d = it.putObject("distractors");
        for (int k = 0; k < 4; k++) {
            if (k != answer) {
                d.put(String.valueOf(k), "[모의] 노린 오개념 " + gibberish(r, 4));
            }
        }
        it.put("x", "[모의 응답] 실제 AI가 만든 해설이 아니라 개발용 가짜 해설입니다.");
        it.put("ev", "[모의] " + name);
        return it;
    }

    private static String gibberish(Random r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append((char) (0xAC00 + r.nextInt(0xD7A3 - 0xAC00 + 1)));
        }
        return sb.toString();
    }
}
