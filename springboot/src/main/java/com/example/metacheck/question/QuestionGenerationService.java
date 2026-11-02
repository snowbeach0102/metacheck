package com.example.metacheck.question;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

/**
 * 문제 생성 한 번의 흐름 — 생성 → 게이트 ① 형식 · ② 규칙 · 중복 검사 → 실패한 문항만 1회 재생성 → 보기 섞기.
 * ai관련/소스/bedrock_out/run-bedrock.mjs 의 runCell 에서 ③ 2차 풀이(검증 호출)만 뺀 것이다.
 */
@Service
public class QuestionGenerationService {

    static final int MAX_ATTEMPTS = 2;
    // 응답 길이 제한. 사양서에 값이 없어 run-bedrock.mjs 의 시작값(2000)을 문항 수만큼 늘렸다.
    static final int TOKENS_PER_QUESTION = 2000;
    static final int MAX_TOKENS = 8000;
    static final double TEMPERATURE = 0.7;

    private final PromptAssembler assembler;
    private final ModelClient client;
    private final IntSupplier exampleSeed;
    private final Supplier<Random> random;

    @Autowired
    public QuestionGenerationService(PromptAssembler assembler, ModelClient client) {
        this(assembler, client, () -> ThreadLocalRandom.current().nextInt(1000), ThreadLocalRandom::current);
    }

    QuestionGenerationService(PromptAssembler assembler, ModelClient client, IntSupplier exampleSeed, Supplier<Random> random) {
        this.assembler = assembler;
        this.client = client;
        this.exampleSeed = exampleSeed;
        this.random = random;
    }

    public GenerateResult generate(GenerateRequest request) {
        PromptInput in = request.toInput();
        PromptAssembler.Prompt prompt = assembler.build(in, exampleSeed.getAsInt());
        int maxTokens = Math.min(TOKENS_PER_QUESTION * in.count(), MAX_TOKENS);

        List<ModelClient.Turn> messages = List.of(new ModelClient.Turn("user", prompt.user()));
        List<JsonNode> accepted = new ArrayList<>();
        List<GenerateResult.Attempt> attempts = new ArrayList<>();
        List<GenerateResult.Skipped> skipped = List.of();

        for (int n = 1; n <= MAX_ATTEMPTS; n++) {
            long t0 = System.currentTimeMillis();
            ModelClient.Reply reply = client.invoke(new ModelClient.Request(prompt.system(), messages, maxTokens, TEMPERATURE));
            String text = reply == null ? null : reply.text();
            Judged j = judge(Json.parseModelJson(text), in, accepted);
            for (Judged.Item item : j.items) {
                if (accepted.size() >= in.count()) {
                    break;
                }
                if (item.errors.isEmpty()) {
                    accepted.add(item.node);
                }
            }
            attempts.add(new GenerateResult.Attempt(n, System.currentTimeMillis() - t0, j.allErrors()));

            if (accepted.size() >= in.count()) {
                break;
            }
            if (!j.skipped.isEmpty() && j.items.isEmpty()) {
                skipped = j.skipped;
                break;
            }
            if (n == MAX_ATTEMPTS) {
                break;
            }
            List<ModelClient.Turn> next = new ArrayList<>(List.of(messages.get(0)));
            next.add(new ModelClient.Turn("assistant", text == null || text.isBlank() ? "(빈 응답)" : text));
            next.add(new ModelClient.Turn("user", feedback(j, in.count(), accepted)));
            messages = List.copyOf(next);
        }

        String status;
        if (accepted.size() >= in.count()) {
            status = attempts.size() > 1 ? "pass-after-retry" : "pass";
        } else if (!accepted.isEmpty()) {
            status = "partial";
        } else {
            status = skipped.isEmpty() ? "fail" : "skipped";
        }

        Random rnd = random.get();
        List<GenerateResult.GeneratedQuestion> questions = accepted.stream().map(item -> toQuestion(item, in, rnd)).toList();
        return new GenerateResult(status, in.count(), questions, List.copyOf(attempts), skipped, prompt.exampleIds());
    }

    private static GenerateResult.GeneratedQuestion toQuestion(JsonNode item, PromptInput in, Random rnd) {
        OptionShuffler.Shuffled s = OptionShuffler.shuffle(item, rnd);
        JsonNode c = item.get("c");
        return new GenerateResult.GeneratedQuestion(
                c != null && c.isString() && !c.asString().isBlank() ? c.asString() : in.concept().id(),
                in.level(),
                strings(item.get("concepts")),
                strings(item.get("steps")),
                item.get("q").asString(),
                s.o(),
                s.a(),
                s.distractors(),
                item.get("x").asString(),
                item.get("ev").asString(),
                s.mode(),
                s.note().isEmpty() ? null : s.note());
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.asString()));
        return List.copyOf(out);
    }

    // ───────── 한 번의 응답을 문항별로 판정

    private static final class Judged {
        static final class Item {
            final JsonNode node;
            final List<String> errors;

            Item(JsonNode node, List<String> errors) {
                this.node = node;
                this.errors = errors;
            }
        }

        final List<String> topErrors = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
        List<GenerateResult.Skipped> skipped = List.of();

        List<String> allErrors() {
            List<String> all = new ArrayList<>(topErrors);
            for (int i = 0; i < items.size(); i++) {
                for (String e : items.get(i).errors) {
                    all.add(items.size() > 1 ? (i + 1) + "번째: " + e : e);
                }
            }
            return all;
        }
    }

    private static Judged judge(JsonNode parsed, PromptInput in, List<JsonNode> accepted) {
        Judged j = new Judged();
        if (parsed == null) {
            j.topErrors.add("JSON 파싱 실패 (JSON 하나만 출력해야 함)");
            return j;
        }
        JsonNode questions = parsed.get("questions");
        if (questions == null || !questions.isArray()) {
            j.topErrors.add("questions 목록이 없음");
            return j;
        }
        if (questions.size() == 0) {
            j.topErrors.add("questions 가 비어 있음");
            j.skipped = readSkipped(parsed.get("skipped"));
            return j;
        }

        // 겹침 비교 대상: 호출자가 알려 준 이미 낸 문항(지문만) + 이번 호출에서 이미 통과한 문항 + 이번 응답에서 먼저 통과한 문항
        List<String> seenQuestionTexts = new ArrayList<>(in.previousQuestions());
        List<String> seenItemTexts = new ArrayList<>();
        accepted.forEach(a -> seenItemTexts.add(QuestionGates.itemText(a)));
        for (JsonNode raw : questions) {
            List<String> errors = check(raw, in.level(), seenQuestionTexts, seenItemTexts);
            if (errors.isEmpty()) {
                seenItemTexts.add(QuestionGates.itemText(raw));
            }
            j.items.add(new Judged.Item(raw, errors));
        }
        return j;
    }

    /** 문항 하나의 게이트 ① · ② + 중복 검사 → 오류 목록 (비면 통과). */
    private static List<String> check(JsonNode item, int lv, List<String> previousQuestionTexts, List<String> seenItemTexts) {
        if (item == null || !item.isObject()) {
            return List.of("문항이 객체가 아님");
        }
        List<String> e1 = QuestionGates.gate1(item);
        if (!e1.isEmpty()) {
            return e1;
        }
        List<String> errors = new ArrayList<>();
        JsonNode itemLv = item.get("lv");
        if (itemLv == null || !itemLv.isNumber() || itemLv.asInt() != lv) {
            errors.add("lv 가 " + (itemLv == null ? "없음" : itemLv.toString()) + " (요청은 " + lv + ")");
        }
        errors.addAll(QuestionGates.gate2(item, lv));

        String text = QuestionGates.itemText(item);
        double worst = 0;
        for (String seen : seenItemTexts) {
            worst = Math.max(worst, QuestionGates.similar(text, seen));
        }
        // 호출자가 준 이미 낸 문항은 지문뿐이라 지문끼리 비교한다 (보기까지 합치면 유사도가 희석돼 놓친다)
        for (String prev : previousQuestionTexts) {
            worst = Math.max(worst, QuestionGates.similar(item.get("q").asString(), prev));
        }
        if (worst >= QuestionGates.DUP) {
            errors.add("다른 문항과 지문 · 보기가 너무 비슷함 (유사도 " + String.format(Locale.ROOT, "%.2f", worst) + ")");
        }
        return errors;
    }

    private static List<GenerateResult.Skipped> readSkipped(JsonNode skipped) {
        if (skipped == null || !skipped.isArray()) {
            return List.of();
        }
        List<GenerateResult.Skipped> out = new ArrayList<>();
        for (JsonNode s : skipped) {
            out.add(new GenerateResult.Skipped(text(s.get("c")), text(s.get("reason"))));
        }
        return List.copyOf(out);
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? "" : n.asString();
    }

    /** 재생성 요청 문구 (run-bedrock.mjs 의 feedbackText 와 같다). 통과한 문항은 다시 쓰지 않고, 문제 있는 문항만 고치게 한다. */
    private static String feedback(Judged j, int count, List<JsonNode> accepted) {
        int need = count - accepted.size();
        List<String> lines = new ArrayList<>();
        lines.add("방금 응답에 다음 문제가 있다.");
        j.topErrors.forEach(e -> lines.add("- " + e));
        boolean anyItemError = false;
        boolean lengthBias = false;
        for (int i = 0; i < j.items.size(); i++) {
            List<String> errs = j.items.get(i).errors;
            if (!errs.isEmpty()) {
                anyItemError = true;
                lines.add("- " + (i + 1) + "번째 문항: " + String.join("; ", errs));
                lengthBias |= errs.stream().anyMatch(e -> e.contains("가장 길다"));
            }
        }
        if (j.topErrors.isEmpty() && !anyItemError) {
            lines.add("- 문항이 모자란다 (요청 " + count + "개, 통과한 것 " + accepted.size() + "개)");
        }
        if (lengthBias) {
            lines.add("정답 보기가 길어 보이지 않도록 오답도 정답과 비슷한 길이 · 구체성으로 쓴다.");
        }
        if (!accepted.isEmpty()) {
            lines.add("이미 통과한 문항은 다시 쓰지 않고, 아래 문항과 겹치지 않게 한다.");
            accepted.forEach(a -> lines.add("[이미 낸 문항] " + a.get("q").asString()));
        }
        lines.add("문제가 있는 문항만 고쳐서 questions 에 " + need + "개를 담아, 같은 형식의 JSON 하나만 다시 출력하라.");
        return String.join("\n", lines);
    }
}
