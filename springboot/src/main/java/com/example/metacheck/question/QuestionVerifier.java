package com.example.metacheck.question;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 게이트 ③ 2차 풀이 — 검증자에게 지문 · 코드 · 보기만 주고 스스로 풀게 한 뒤, 정답 일치 · 정답 하나 · 근거를 서버가 판정한다.
 * 규칙은 ai관련/bedrock_12칸/run-bedrock.mjs 의 buildVerifyRequest · deriveVerify · verifyOne 과 같다.
 * 정답 · 해설 · steps · distractors 는 보내지 않는다 — 주면 검증자가 답을 따라가기만 해서 검증이 되지 않는다(사양서 8쪽).
 */
@Component
public class QuestionVerifier {

    // 사양서 초안 값(assemble-bedrock.js 의 meta.verify.inferenceConfig 와 같다)
    static final int MAX_TOKENS = 600;
    static final double TEMPERATURE = 0;
    /** 검증자 응답이 형식에 맞지 않으면 한 번 더 묻는다 — 그래도 안 되면 검증하지 못한 채 통과(Node 와 같다). */
    static final int CALLS = 2;
    private static final String[] LEVEL_LABEL = {null, "기초", "실전", "심화"};

    /** 판정. valid=false 면 검증하지 못한 것이고 errors 는 비어 있다(note 에 이유). stage · stageOk 는 기록용 — 재생성 사유가 아니다. */
    record Verdict(boolean valid, List<String> errors, Integer stage, boolean stageOk, String issue, String note) {

        GenerateResult.Verify toResult() {
            return valid ? new GenerateResult.Verify(true, stage, stageOk, issue.isEmpty() ? null : issue, null)
                    : new GenerateResult.Verify(false, null, null, null, note);
        }
    }

    private final PromptTemplates templates;

    public QuestionVerifier(PromptTemplates templates) {
        this.templates = templates;
    }

    /** 문항 하나를 검증자에게 풀게 한다. 모델 호출 실패(ModelCallException)는 그대로 올라간다. */
    Verdict verify(ModelClient client, PromptInput in, JsonNode item, ModelClient.Tier tier) {
        ModelClient.Request request = new ModelClient.Request(templates.verifySystem(),
                List.of(new ModelClient.Turn("user", userText(in, item))), MAX_TOKENS, TEMPERATURE, tier);
        for (int k = 0; k < CALLS; k++) {
            ModelClient.Reply reply = client.invoke(request);
            Verdict v = judge(Json.parseModelJson(reply == null ? null : reply.text()), item, in.level());
            if (v.valid()) {
                return v;
            }
        }
        return new Verdict(false, List.of(), null, false, "", "검증 응답 형식 오류 — 검증하지 못함");
    }

    /** 검증 USER 글. 채우는 순서 · 자료 줄 처리는 Node 의 buildVerifyRequest 와 같다. */
    String userText(PromptInput in, JsonNode item) {
        ObjectNode question = Json.MAPPER.createObjectNode();
        question.put("q", item.get("q").asString());
        String code = QuestionGates.code(item);
        if (!code.isEmpty()) {
            question.put("code", code);
        }
        question.set("o", item.get("o"));

        String user = templates.verifyUser()
                .replace("{{subject}}", in.subject())
                .replace("{{level_label}}", LEVEL_LABEL[in.level()])
                .replace("{{level}}", String.valueOf(in.level()))
                .replace("{{question_json}}", Json.MAPPER.writeValueAsString(question))
                .replace("{{level_criteria}}", templates.levelCriteria() + "\n");
        if (!in.material().isEmpty()) {
            return user.replace("{{material}}", in.material());
        }
        List<String> lines = new ArrayList<>();
        for (String line : user.split("\n", -1)) {
            if (!line.contains("{{material}}")) {   // 자료가 없으면 줄 생략
                lines.add(line);
            }
        }
        return String.join("\n", lines);
    }

    /** 검증자의 답(correct · grounded · stage)에서 정답 일치 · 정답 하나 · 근거를 판정한다 (Node 의 deriveVerify). */
    static Verdict judge(JsonNode parsed, JsonNode item, int lv) {
        JsonNode correctNode = parsed == null ? null : parsed.get("correct");
        JsonNode grounded = parsed == null ? null : parsed.get("grounded");
        JsonNode stage = parsed == null ? null : parsed.get("stage");
        boolean okShape = correctNode != null && correctNode.isArray() && grounded != null && grounded.isBoolean()
                && isIntIn(stage, 1, 3);
        TreeSet<Integer> correct = new TreeSet<>();
        if (okShape) {
            for (JsonNode c : correctNode) {
                okShape &= isIntIn(c, 0, 3);
                if (c.isNumber()) {
                    correct.add(c.asInt());
                }
            }
        }
        if (!okShape) {
            return new Verdict(false, List.of(), null, false, "", "검증 응답 형식 오류");
        }

        int a = item.get("a").asInt();
        List<String> errors = new ArrayList<>();
        if (correct.isEmpty()) {
            errors.add("③ 검증자가 맞는 보기를 찾지 못함");
        } else if (correct.size() > 1) {
            errors.add("③ 정답이 2개 이상으로 보임 (검증자: " + String.join(", ", correct.stream().map(String::valueOf).toList()) + ")");
        } else if (correct.first() != a) {
            errors.add("③ 정답 불일치 (출제 " + a + ", 검증자 " + correct.first() + ")");
        }
        if (!grounded.asBoolean()) {
            errors.add("③ 자료 · 원리로 설명되지 않는다고 판단함");
        }
        JsonNode issue = parsed.get("issue");
        String issueText = issue != null && issue.isString() ? issue.asString() : "";
        return new Verdict(true, List.copyOf(errors), stage.asInt(), stage.asInt() == lv, issueText, null);
    }

    // JS 의 Number.isInteger 처럼 2.0 도 정수로 본다
    private static boolean isIntIn(JsonNode n, int min, int max) {
        return n != null && n.isNumber() && n.asDouble() == Math.rint(n.asDouble()) && n.asDouble() >= min && n.asDouble() <= max;
    }
}
