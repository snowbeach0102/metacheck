package com.example.metacheck.question;

import java.util.Iterator;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 모델 응답 해석 · 예시 출력에 쓰는 JSON 도구. */
final class Json {

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /**
     * 모델 응답에서 JSON 객체 하나를 꺼낸다. ```json 울타리와 앞뒤 설명은 무시하고, 못 읽으면 null.
     * (run-bedrock.mjs 의 parseModelJson 과 같은 규칙)
     */
    static JsonNode parseModelJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip().replaceFirst("(?i)^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "").strip();
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a < 0 || b < a) {
            return null;
        }
        try {
            return MAPPER.readTree(t.substring(a, b + 1));
        } catch (JacksonException e) {
            return null;
        }
    }

    /** JavaScript 의 JSON.stringify(value, null, 2) 와 같은 모양(들여쓰기 2칸 · "key": value)으로 쓴다. 프롬프트 [예시]가 Node 조립기와 같아야 해서 따로 만들었다. */
    static String pretty(JsonNode n) {
        StringBuilder sb = new StringBuilder();
        write(sb, n, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, JsonNode n, int indent) {
        if (n.isObject()) {
            if (n.size() == 0) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            Iterator<Map.Entry<String, JsonNode>> it = n.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sb.append(" ".repeat(indent + 2)).append(quote(e.getKey())).append(": ");
                write(sb, e.getValue(), indent + 2);
                sb.append(it.hasNext() ? ",\n" : "\n");
            }
            sb.append(" ".repeat(indent)).append('}');
        } else if (n.isArray()) {
            if (n.size() == 0) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < n.size(); i++) {
                sb.append(" ".repeat(indent + 2));
                write(sb, n.get(i), indent + 2);
                sb.append(i < n.size() - 1 ? ",\n" : "\n");
            }
            sb.append(" ".repeat(indent)).append(']');
        } else if (n.isString()) {
            sb.append(quote(n.asString()));
        } else {
            sb.append(n.toString());
        }
    }

    private static String quote(String s) {
        return MAPPER.writeValueAsString(s);
    }
}
