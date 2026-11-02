package com.example.metacheck.question;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * 서버 보기 섞기 (사양서 부록 · ai관련/소스/bedrock_out/run-bedrock.mjs 의 shuffleOptions 와 같은 규칙).
 * 모델이 shuffle 표시를 빼먹거나 true 로 써도 조합형(ㄱ, ㄴ) · 숫자형 · 숫자 목록형 · 앞 순서형(제N… / N단계…) 보기는
 * 섞지 않고 시험지 관례대로 정렬한다. 모델이 shuffle:false 로 쓴 문항도 그대로 둔다. 그 밖에는 무작위로 섞고
 * 정답 번호 a 와 distractors 의 번호를 다시 계산한다.
 */
final class OptionShuffler {

    /** 섞은 결과. mode 는 random · kept-model · sorted-combo/numeric/ordinal, note 는 서버가 모델과 다르게 처리했을 때의 설명. */
    record Shuffled(List<String> o, int a, Map<Integer, String> distractors, String mode, String note) {
    }

    private static final Pattern NUM = Pattern.compile("(?<![\\d.])([+\\-−])?(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?");
    private static final Pattern COMBO = Pattern.compile("^[ㄱ-ㅎ](?:\\s*[,·]\\s*[ㄱ-ㅎ])*$");
    private static final Pattern ORDINAL = Pattern.compile("^(?:제\\s*(\\d+)|(\\d+)\\s*(?:단계|차|번째))");
    private static final Pattern LIST = Pattern.compile("^#(?:\\s*[,·]\\s*#)*$");

    private OptionShuffler() {
    }

    /** 고정 순서로 정렬해야 하는 보기면 (종류, 새 순서)를, 아니면 null. */
    record FixedOrder(String kind, int[] order) {
    }

    static FixedOrder detectFixedOrder(List<String> options) {
        if (options == null || options.size() < 2) {
            return null;
        }
        List<String> t = options.stream().map(String::strip).toList();

        if (t.stream().allMatch(s -> COMBO.matcher(s).matches())) {
            List<double[]> keys = new ArrayList<>();
            for (String s : t) {
                String[] parts = s.split("[,·]");
                double[] key = new double[parts.length + 1];
                key[0] = parts.length;
                for (int i = 0; i < parts.length; i++) {
                    key[i + 1] = parts[i].strip().charAt(0);
                }
                keys.add(key);
            }
            return new FixedOrder("combo", sortedOrder(keys));
        }

        List<Parsed> parsed = t.stream().map(OptionShuffler::parseNums).toList();
        boolean allHaveNumber = parsed.stream().allMatch(p -> p.nums.length >= 1);
        if (allHaveNumber && (parsed.stream().allMatch(p -> p.residue.equals(parsed.get(0).residue))
                || parsed.stream().allMatch(p -> LIST.matcher(p.residue).matches()))) {
            return new FixedOrder("numeric", sortedOrder(parsed.stream().map(p -> p.nums).toList()));
        }

        List<double[]> ordinals = new ArrayList<>();
        for (String s : t) {
            Matcher m = ORDINAL.matcher(s);
            if (!m.find()) {
                return null;
            }
            ordinals.add(new double[] {Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2))});
        }
        if (ordinals.stream().map(k -> k[0]).distinct().count() > 1) {
            return new FixedOrder("ordinal", sortedOrder(ordinals));
        }
        return null;
    }

    private record Parsed(double[] nums, String residue) {
    }

    // 보기에서 숫자를 모두 뽑고 그 자리를 #으로 바꾼 나머지 글(residue)을 돌려준다
    private static Parsed parseNums(String s) {
        List<Double> nums = new ArrayList<>();
        Matcher m = NUM.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            double v = Double.parseDouble(m.group(2).replace(",", "") + (m.group(3) == null ? "" : m.group(3)));
            String sign = m.group(1);
            nums.add(sign != null && !sign.equals("+") ? -v : v);
            m.appendReplacement(sb, "#");
        }
        m.appendTail(sb);
        return new Parsed(nums.stream().mapToDouble(Double::doubleValue).toArray(), sb.toString().replaceAll("\\s+", " ").strip());
    }

    // 키를 작은 것부터 늘어놓은 원래 번호 순서. 같은 키는 모델이 쓴 순서를 유지한다.
    private static int[] sortedOrder(List<double[]> keys) {
        Integer[] idx = new Integer[keys.size()];
        Arrays.setAll(idx, i -> i);
        Arrays.sort(idx, (x, y) -> {
            int c = compare(keys.get(x), keys.get(y));
            return c != 0 ? c : Integer.compare(x, y);
        });
        return Arrays.stream(idx).mapToInt(Integer::intValue).toArray();
    }

    private static int compare(double[] a, double[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) {
                return Double.compare(a[i], b[i]);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    /** 통과한 문항 하나를 섞는다. item 은 gate1 을 통과한 문항이어야 한다. */
    static Shuffled shuffle(JsonNode item, Random rnd) {
        List<String> o = new ArrayList<>();
        item.get("o").forEach(x -> o.add(x.asString()));
        int a = item.get("a").asInt();
        boolean shuffleFlag = item.get("shuffle").asBoolean();

        FixedOrder fixed = detectFixedOrder(o);
        int[] idx;
        String mode;
        String note = "";
        if (fixed != null) {
            idx = fixed.order();
            mode = "sorted-" + fixed.kind();
            if (shuffleFlag) {
                String label = switch (fixed.kind()) {
                    case "combo" -> "조합형";
                    case "numeric" -> "숫자형";
                    default -> "순서형";
                };
                note = "서버가 " + label + " 보기를 감지해 섞지 않음 (모델은 shuffle:true 로 씀)";
            }
        } else if (!shuffleFlag) {
            idx = new int[] {0, 1, 2, 3};
            mode = "kept-model";
        } else {
            idx = new int[] {0, 1, 2, 3};
            mode = "random";
            for (int i = 3; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int tmp = idx[i];
                idx[i] = idx[j];
                idx[j] = tmp;
            }
        }

        List<String> shuffled = new ArrayList<>();
        Map<Integer, String> distractors = new TreeMap<>();
        int newA = -1;
        for (int newI = 0; newI < idx.length; newI++) {
            int oldI = idx[newI];
            shuffled.add(o.get(oldI));
            if (oldI == a) {
                newA = newI;
            } else {
                distractors.put(newI, item.get("distractors").get(String.valueOf(oldI)).asString());
            }
        }
        return new Shuffled(List.copyOf(shuffled), newA, distractors, mode, note);
    }
}
