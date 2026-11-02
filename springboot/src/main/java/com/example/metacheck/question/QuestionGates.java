package com.example.metacheck.question;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * 생성된 문항을 사용자에게 보내기 전에 거르는 서버 검사 — 사양서 5장의 게이트 ① 형식 · ② 규칙, 그리고 중복 검사.
 * 기준은 ai관련/소스/bedrock_out/run-bedrock.mjs 와 같다. 숫자(1.25배 · 15자 · 0.75)는 사양서의 <b>초안</b>이라
 * 실제 모델 출력을 보고 조정해야 한다.
 */
final class QuestionGates {

    /** 정답 보기가 가장 길고 오답 평균의 이 배수를 넘으면 실패. */
    static final double BIAS = 1.25;
    /** 보기 평균이 이보다 짧은 숫자 · 단답형은 길이 검사에서 뺀다. */
    static final int SHORT_MEAN = 15;
    /** 지문 + 보기 유사도(Dice)가 이 값 이상이면 중복. */
    static final double DUP = 0.75;

    // 단계별 기준: concepts 개수 [최소, 최대], steps 개수 [최소, 최대], 지문 최대 문장 수. 최대 99 는 "이상".
    private static final int[][] CONCEPTS = {null, {1, 1}, {1, 2}, {2, 99}};
    private static final int[][] STEPS = {null, {0, 0}, {1, 2}, {3, 99}};
    private static final int[] MAX_SENTENCES = {0, 2, 3, 3};

    // 보기 번호 · 보기 참조 표현. 'N번' 은 Node 쪽 정규식(\b[1-4]번\b)이 한글 문장에서는 동작하지 않아 의도대로 다시 썼다.
    private static final Pattern FORBIDDEN = Pattern.compile("[①②③④⑤]|(?<![0-9])[1-4]번(?!째)|위의 모두|모두 옳|모두 틀|해당 없음|위 보기");
    private static final Pattern SENTENCE = Pattern.compile("[^.?!]+(?:(?<!\\d)[.?!](?!\\d)|$)");
    private static final Pattern IGNORED_FOR_SIMILARITY = Pattern.compile("[\\s.,?!()'\"“”‘’·\\-—~:;]");

    private QuestionGates() {
    }

    // ───────── 게이트 ① 형식: 모양이 맞는가 (JSON 필드 · 보기 4개 · 정답 번호)
    static List<String> gate1(JsonNode it) {
        List<String> e = new ArrayList<>();
        JsonNode o = it.get("o");
        if (o == null || !o.isArray() || o.size() != 4) {
            e.add("보기가 4개가 아님");
        } else {
            boolean allText = true;
            TreeSet<String> distinct = new TreeSet<>();
            for (JsonNode x : o) {
                allText &= x.isString();
                distinct.add(x.toString());
            }
            if (!allText) {
                e.add("보기가 문자열이 아님");
            } else if (distinct.size() != o.size()) {
                e.add("보기 중복");
            }
        }

        JsonNode a = it.get("a");
        boolean aInt = a != null && a.isNumber() && a.asDouble() == Math.rint(a.asDouble());
        if (!aInt || a.asInt() < 0 || a.asInt() > 3) {
            e.add("a 가 0~3 정수가 아님");
        }

        for (String f : new String[] {"concepts", "steps", "q", "x", "ev"}) {
            if (isMissing(it.get(f))) {
                e.add(f + " 없음");
            }
        }
        for (String f : new String[] {"q", "x", "ev"}) {
            JsonNode v = it.get(f);
            if (!isMissing(v) && !v.isString()) {
                e.add(f + " 가 문자열이 아님");
            }
        }
        JsonNode concepts = it.get("concepts");
        if (!isMissing(concepts) && !concepts.isArray()) {
            e.add("concepts 가 목록이 아님");
        }
        JsonNode steps = it.get("steps");
        if (!isMissing(steps) && !steps.isArray()) {
            e.add("steps 가 목록이 아님");
        }

        JsonNode d = it.get("distractors");
        if (d == null || !d.isObject()) {
            e.add("distractors 없음");
        } else if (aInt) {   // 정답 번호가 범위를 벗어났어도 정수이면 키를 비교한다 (Node 원본과 같다)
            TreeSet<String> need = new TreeSet<>();
            for (int i = 0; i < 4; i++) {
                if (i != a.asInt()) {
                    need.add(String.valueOf(i));
                }
            }
            TreeSet<String> have = new TreeSet<>(d.propertyNames());
            if (!have.equals(need)) {
                e.add("distractors 키가 정답을 뺀 3개와 다름");
            }
        }

        JsonNode shuffle = it.get("shuffle");
        if (shuffle == null || !shuffle.isBoolean()) {
            e.add("shuffle 이 true/false 가 아님");
        }
        return e;
    }

    // ───────── 게이트 ② 규칙: 단계 기준 · 금지 표현 · 정답 길이 편향. gate1 을 통과한 문항에만 부른다.
    static List<String> gate2(JsonNode it, int lv) {
        List<String> e = new ArrayList<>();
        int nc = it.get("concepts").size();
        int ns = it.get("steps").size();
        int[] c = CONCEPTS[lv];
        int[] s = STEPS[lv];
        if (nc < c[0] || nc > c[1]) {
            e.add("concepts " + nc + "개 (기준 " + range(c) + ")");
        }
        if (ns < s[0] || ns > s[1]) {
            e.add("steps " + ns + "개 (기준 " + range(s) + ")");
        }
        int sc = sentences(it.get("q").asString());
        if (sc > MAX_SENTENCES[lv]) {
            e.add("지문 " + sc + "문장 (최대 " + MAX_SENTENCES[lv] + ")");
        }

        List<String> all = new ArrayList<>();
        all.add(it.get("q").asString());
        it.get("o").forEach(x -> all.add(x.asString()));
        all.add(it.get("x").asString());
        it.get("distractors").forEach(x -> all.add(x.asString()));
        if (FORBIDDEN.matcher(String.join("\n", all)).find()) {
            e.add("금지 표현(보기 번호 · 보기 참조)");
        }

        int answer = it.get("a").asInt();
        int[] len = new int[4];
        for (int i = 0; i < 4; i++) {
            len[i] = it.get("o").get(i).asString().length();
        }
        double wrongSum = 0;
        boolean isMax = true;
        for (int i = 0; i < 4; i++) {
            if (i != answer) {
                wrongSum += len[i];
                isMax &= len[i] < len[answer];
            }
        }
        double ratio = len[answer] / (wrongSum / 3);
        double meanAll = (len[0] + len[1] + len[2] + len[3]) / 4.0;
        if (meanAll >= SHORT_MEAN && isMax && ratio > BIAS) {
            e.add("정답 보기가 가장 길다 (오답 평균의 " + String.format(Locale.ROOT, "%.2f", ratio) + "배)");
        }
        return e;
    }

    private static String range(int[] r) {
        return r[0] + (r[1] == 99 ? "개 이상" : "~" + r[1] + "개");
    }

    private static boolean isMissing(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
    }

    /** 문장 수. 소수점 · 번호 뒤의 마침표(3.5, 1.)는 문장 끝으로 보지 않는다. */
    static int sentences(String t) {
        int n = 0;
        Matcher m = SENTENCE.matcher(t == null ? "" : t);
        while (m.find()) {
            if (!m.group().isBlank()) {
                n++;
            }
        }
        return n;
    }

    // ───────── 중복 검사: 글자 두 개씩 끊어 센 Dice 유사도
    static double similar(String a, String b) {
        Map<String, Integer> ga = bigrams(a);
        Map<String, Integer> gb = bigrams(b);
        int na = ga.values().stream().mapToInt(Integer::intValue).sum();
        int nb = gb.values().stream().mapToInt(Integer::intValue).sum();
        if (na == 0 || nb == 0) {
            return 0;
        }
        int inter = 0;
        for (Map.Entry<String, Integer> en : ga.entrySet()) {
            Integer other = gb.get(en.getKey());
            if (other != null) {
                inter += Math.min(en.getValue(), other);
            }
        }
        return 2.0 * inter / (na + nb);
    }

    private static Map<String, Integer> bigrams(String s) {
        String t = IGNORED_FOR_SIMILARITY.matcher(s == null ? "" : s).replaceAll("");
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < t.length() - 1; i++) {
            m.merge(t.substring(i, i + 2), 1, Integer::sum);
        }
        return m;
    }

    /** 중복 비교용 글 — 지문만 비교하면 "~의 정의로 가장 알맞은 것은?" 같은 틀이 같은 서로 다른 문항이 중복으로 걸린다. */
    static String itemText(JsonNode it) {
        StringBuilder sb = new StringBuilder(it.get("q").asString());
        for (JsonNode o : it.get("o")) {
            sb.append(' ').append(o.asString());
        }
        return sb.toString();
    }
}
