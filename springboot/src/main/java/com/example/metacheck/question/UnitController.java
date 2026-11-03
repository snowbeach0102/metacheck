package com.example.metacheck.question;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정보처리산업기사 단원표와 단원별 출제 API (11/03). 앱은 아직 연결하지 않았다(mock) — 실제 Bedrock 을 켠 뒤에 붙인다.
 * 로그인 · 호출 횟수 제한이 없으니 실제 Bedrock 을 켜기 전에 막아야 한다(QuestionController 와 같다).
 */
@RestController
@RequestMapping("/api/v1/ai/units")
public class UnitController {

    /** 단원표 전체 — 과목 → 주요항목 → 단원(개념 3개). */
    public record UnitTable(String exam, List<ExamUnits.Subject> subjects) {
    }

    private final ExamUnits units;
    private final UnitQuestionService service;

    public UnitController(ExamUnits units, UnitQuestionService service) {
        this.units = units;
        this.service = service;
    }

    @GetMapping
    public UnitTable list() {
        return new UnitTable(units.exam(), units.subjects());
    }

    @GetMapping("/{unitId}")
    public ExamUnits.Unit get(@PathVariable String unitId) {
        return units.find(unitId).orElseThrow(() -> new UnitQuestionService.UnitNotFoundException(unitId));
    }

    /** 단원 진단 문항 만들기. 본문을 비우면 개념 3개 × 1 · 2 · 3단계 × 1문항(9문항). */
    @PostMapping("/{unitId}/questions")
    public UnitQuestionService.UnitQuestions generate(@PathVariable String unitId,
            @RequestBody(required = false) UnitQuestionService.UnitRequest request) {
        return service.generate(unitId, request);
    }

    @ExceptionHandler(UnitQuestionService.UnitNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(UnitQuestionService.UnitNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ModelCallException.class)
    ResponseEntity<Map<String, String>> badGateway(ModelCallException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "AI 호출에 실패했습니다. 잠시 뒤 다시 시도해 주세요."));
    }
}
