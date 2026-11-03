package com.example.metacheck.question;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

/**
 * 단원 하나로 진단 문항을 만든다 — 단원표의 개념 3개 × 고른 단계마다 {@link QuestionGenerationService} 를 한 번씩 부른다.
 * 진단 한 번 = 단원 하나(11/03 사용자 결정). 여러 단원을 섞는 과목 진단과 그 비율은 팀이 정할 일이라 아직 없다.
 * 수험생 역할로 고정하고(시험은 서버가 정보처리산업기사로 넣는다), 3단계에서 연결할 [관련 개념]은 같은 단원의 나머지 두 개념이다.
 */
@Service
public class UnitQuestionService {

    /** (개념 × 단계) 한 칸의 문항 수 상한과 한 요청의 전체 문항 상한 — 실제 모델은 호출마다 비용이 들어서 묶어 둔다(초안). */
    static final int MAX_PER_CELL = 3;
    static final int MAX_TOTAL = 15;
    static final List<Integer> ALL_LEVELS = List.of(1, 2, 3);
    // 칸마다 호출을 병렬로 보낸다(각 칸 안의 2차 풀이는 생성 서비스의 풀을 따로 쓴다)
    private static final ExecutorService CELL_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "unit-cell");
        t.setDaemon(true);
        return t;
    });

    /** 단원 진단 문항 요청. levels 가 없으면 1 · 2 · 3단계 모두, count 는 칸마다 문항 수(없으면 1), userRequest 는 스타일 힌트. */
    public record UnitRequest(List<Integer> levels, Integer count, String userRequest) {
    }

    /** 칸 하나(개념 × 단계)의 생성 결과. */
    public record Cell(String conceptId, String conceptName, int level, GenerateResult result) {
    }

    /** requested · passed 는 모든 칸을 합친 요청 문항 수 · 검사를 통과한 문항 수. */
    public record UnitQuestions(String exam, ExamUnits.Unit unit, int requested, int passed, List<Cell> cells) {
    }

    /** 단원표에 없는 단원. */
    public static class UnitNotFoundException extends RuntimeException {
        public UnitNotFoundException(String unitId) {
            super("단원을 찾을 수 없습니다: " + unitId);
        }
    }

    private final ExamUnits units;
    private final QuestionGenerationService generator;

    public UnitQuestionService(ExamUnits units, QuestionGenerationService generator) {
        this.units = units;
        this.generator = generator;
    }

    public UnitQuestions generate(String unitId, UnitRequest request) {
        ExamUnits.Unit unit = units.find(unitId).orElseThrow(() -> new UnitNotFoundException(unitId));
        UnitRequest req = request == null ? new UnitRequest(null, null, null) : request;
        List<Integer> levels = levels(req.levels());
        int count = req.count() == null ? 1 : req.count();
        if (count < 1 || count > MAX_PER_CELL) {
            throw new IllegalArgumentException("count(칸마다 문항 수)는 1~" + MAX_PER_CELL + " 이어야 합니다.");
        }
        int total = unit.concepts().size() * levels.size() * count;
        if (total > MAX_TOTAL) {
            throw new IllegalArgumentException("한 번에 만들 문항이 " + total + "개입니다. 개념 3개 × 단계 수 × count 가 " + MAX_TOTAL + "개 이하여야 합니다.");
        }

        record Task(ConceptRef concept, int level, GenerateRequest request) {
        }
        List<Task> tasks = new ArrayList<>();
        for (ConceptRef concept : unit.concepts()) {
            List<ConceptRef> related = unit.concepts().stream().filter(c -> !c.id().equals(concept.id()))
                    .map(c -> new ConceptRef(null, c.name(), c.desc())).toList();
            for (int lv : levels) {
                tasks.add(new Task(concept, lv, new GenerateRequest(Persona.CERT_EXAM, unit.subject(), unit.name(), null, concept, related, lv, count,
                        null, null, req.userRequest())));
            }
        }

        List<CompletableFuture<GenerateResult>> futures = tasks.stream()
                .map(t -> CompletableFuture.supplyAsync(() -> generator.generate(t.request()), CELL_POOL)).toList();
        List<Cell> cells = new ArrayList<>();
        try {
            for (int i = 0; i < tasks.size(); i++) {
                Task t = tasks.get(i);
                cells.add(new Cell(t.concept().id(), t.concept().name(), t.level(), futures.get(i).join()));
            }
        } catch (CompletionException e) {
            // 모델 호출 실패(→ 502) · 잘못된 요청(→ 400)을 감싸지 않고 그대로 올린다
            throw e.getCause() instanceof RuntimeException re ? re : e;
        }
        int passed = cells.stream().mapToInt(c -> c.result().questions().size()).sum();
        return new UnitQuestions(units.exam(), unit, total, passed, List.copyOf(cells));
    }

    private static List<Integer> levels(List<Integer> levels) {
        if (levels == null) {
            return ALL_LEVELS;
        }
        TreeSet<Integer> set = new TreeSet<>();
        for (Integer lv : levels) {
            if (lv == null || lv < 1 || lv > 3) {
                throw new IllegalArgumentException("levels(단계)는 1~3 이어야 합니다.");
            }
            set.add(lv);
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException("levels(단계)가 비어 있습니다.");
        }
        return List.copyOf(set);
    }
}
