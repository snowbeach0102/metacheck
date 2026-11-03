package com.example.metacheck.question;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

/**
 * 문제 생성 한 번의 흐름 — 생성 → 게이트 ① 형식 · ② 규칙 · 중복 검사 → ③ 2차 풀이 → 실패한 문항만 1회 재생성 → 보기 섞기.
 * ai관련/bedrock_12칸/run-bedrock.mjs 의 runCell 과 같은 흐름이다. ③은 설정 metacheck.ai.verify 로 끌 수 있다(기본 켬).
 */
@Service
public class QuestionGenerationService {

    static final int MAX_ATTEMPTS = 2;
    // 응답 길이 제한. 사양서에 값이 없어 run-bedrock.mjs 의 시작값(2000)을 문항 수만큼 늘렸다.
    static final int TOKENS_PER_QUESTION = 2000;
    static final int MAX_TOKENS = 8000;
    static final double TEMPERATURE = 0.7;
    // 2차 풀이는 문항마다 병렬로 보낸다(사양서 8쪽). 호출 대기가 대부분이라 작은 공용 풀로 충분하다
    private static final ExecutorService VERIFY_POOL = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "question-verify");
        t.setDaemon(true);
        return t;
    });

    private final PromptAssembler assembler;
    private final ModelClient client;
    private final QuestionVerifier verifier;   // null 이면 ③ 2차 풀이를 하지 않는다
    private final IntSupplier exampleSeed;
    private final Supplier<Random> random;

    @Autowired
    public QuestionGenerationService(PromptAssembler assembler, ModelClient client, QuestionVerifier verifier,
            @Value("${metacheck.ai.verify:true}") boolean verify) {
        this(assembler, client, verify ? verifier : null, () -> ThreadLocalRandom.current().nextInt(1000), ThreadLocalRandom::current);
    }

    QuestionGenerationService(PromptAssembler assembler, ModelClient client, QuestionVerifier verifier, IntSupplier exampleSeed, Supplier<Random> random) {
        this.assembler = assembler;
        this.client = client;
        this.verifier = verifier;
        this.exampleSeed = exampleSeed;
        this.random = random;
    }

    public GenerateResult generate(GenerateRequest request) {
        PromptInput in = request.toInput();
        PromptAssembler.Prompt prompt = assembler.build(in, exampleSeed.getAsInt());
        int maxTokens = Math.min(TOKENS_PER_QUESTION * in.count(), MAX_TOKENS);
        // 팀 결정: 직업군과 상관없이 3단계(심화)만 상위 모델. 2차 풀이도 같은 등급(사양서: 1 · 2단계 경량, 심화 상위)
        ModelClient.Tier tier = in.level() == 3 ? ModelClient.Tier.ADVANCED : ModelClient.Tier.BASIC;

        List<ModelClient.Turn> messages = List.of(new ModelClient.Turn("user", prompt.user()));
        List<JsonNode> accepted = new ArrayList<>();
        List<QuestionVerifier.Verdict> verdicts = new ArrayList<>();
        List<GenerateResult.Attempt> attempts = new ArrayList<>();
        List<GenerateResult.Skipped> skipped = List.of();

        for (int n = 1; n <= MAX_ATTEMPTS; n++) {
            long t0 = System.currentTimeMillis();
            ModelClient.Reply reply = client.invoke(new ModelClient.Request(prompt.system(), messages, maxTokens, TEMPERATURE, tier));
            String text = reply == null ? null : reply.text();
            Judged j = judge(Json.parseModelJson(text), in, accepted);
            if (verifier != null) {
                verifyPassed(j, in, tier);
            }
            for (Judged.Item item : j.items) {
                if (accepted.size() >= in.count()) {
                    break;
                }
                if (item.errors.isEmpty()) {
                    accepted.add(item.node);
                    verdicts.add(item.verdict);
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
        List<GenerateResult.GeneratedQuestion> questions = new ArrayList<>();
        for (int i = 0; i < accepted.size(); i++) {
            questions.add(toQuestion(accepted.get(i), verdicts.get(i), in, rnd));
        }
        return new GenerateResult(status, in.count(), List.copyOf(questions), List.copyOf(attempts), skipped, prompt.exampleIds());
    }

    /** 게이트 ① · ②를 통과한 문항만 2차 풀이에 보낸다(걸린 문항에 검증 호출을 낭비하지 않는다). 여러 문항이면 병렬로. */
    private void verifyPassed(Judged j, PromptInput in, ModelClient.Tier tier) {
        List<Judged.Item> todo = j.items.stream().filter(i -> i.errors.isEmpty()).toList();
        List<QuestionVerifier.Verdict> results;
        if (todo.size() <= 1) {
            results = todo.stream().map(i -> verifier.verify(client, in, i.node, tier)).toList();
        } else {
            List<CompletableFuture<QuestionVerifier.Verdict>> futures = todo.stream()
                    .map(i -> CompletableFuture.supplyAsync(() -> verifier.verify(client, in, i.node, tier), VERIFY_POOL)).toList();
            try {
                results = futures.stream().map(CompletableFuture::join).toList();
            } catch (CompletionException e) {
                // 모델 호출 실패(ModelCallException → 502)를 감싸지 않고 그대로 올린다
                throw e.getCause() instanceof RuntimeException re ? re : e;
            }
        }
        for (int i = 0; i < todo.size(); i++) {
            todo.get(i).verdict = results.get(i);
            todo.get(i).errors.addAll(results.get(i).errors());
        }
    }

    private static GenerateResult.GeneratedQuestion toQuestion(JsonNode item, QuestionVerifier.Verdict verdict, PromptInput in, Random rnd) {
        OptionShuffler.Shuffled s = OptionShuffler.shuffle(item, rnd);
        JsonNode c = item.get("c");
        String code = QuestionGates.code(item);
        return new GenerateResult.GeneratedQuestion(
                c != null && c.isString() && !c.asString().isBlank() ? c.asString() : in.concept().id(),
                in.level(),
                strings(item.get("concepts")),
                strings(item.get("steps")),
                item.get("q").asString(),
                code.isBlank() ? null : code,
                s.o(),
                s.a(),
                s.distractors(),
                item.get("x").asString(),
                item.get("ev").asString(),
                s.mode(),
                s.note().isEmpty() ? null : s.note(),
                verdict == null ? null : verdict.toResult());
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
            QuestionVerifier.Verdict verdict;

            Item(JsonNode node, List<String> errors) {
                this.node = node;
                this.errors = new ArrayList<>(errors);
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

        // 겹침 비교 대상: 호출자가 알려 준 이미 낸 문항(지문 + 코드 한 줄) + 이번 호출에서 이미 통과한 문항 + 이번 응답에서 먼저 통과한 문항
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
        // 호출자가 준 이미 낸 문항은 지문(+ 코드)뿐이라 지문 + 코드끼리 비교한다 (보기까지 합치면 유사도가 희석돼 놓친다).
        // 호출자 글은 입력 정리(중괄호 · 줄바꿈 제거)를 거쳤으므로 이쪽도 같게 정리해서 비교한다
        String stem = InputCleaner.cleanLine(QuestionGates.stemLine(item), InputCleaner.PREVIOUS_MAX);
        for (String prev : previousQuestionTexts) {
            worst = Math.max(worst, QuestionGates.similar(stem, prev));
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
            accepted.forEach(a -> lines.add("[이미 낸 문항] " + QuestionGates.stemLine(a)));
        }
        lines.add("문제가 있는 문항만 고쳐서 questions 에 " + need + "개를 담아, 같은 형식의 JSON 하나만 다시 출력하라.");
        return String.join("\n", lines);
    }
}
