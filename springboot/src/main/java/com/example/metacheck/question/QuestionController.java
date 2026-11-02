package com.example.metacheck.question;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문제 생성 확인용 API. 실제 진단 흐름(POST /diagnoses 등)은 {@link QuestionGenerationService} 를 서버 안에서 직접 부르면 된다.
 * 아직 로그인 · 호출 횟수 제한이 없으니, 가짜 모델이 아닌 실제 Bedrock 을 연결할 때는 먼저 막아야 한다(호출마다 비용이 든다).
 */
@RestController
@RequestMapping("/api/v1/ai")
public class QuestionController {

    private final QuestionGenerationService service;

    public QuestionController(QuestionGenerationService service) {
        this.service = service;
    }

    @PostMapping("/questions")
    public GenerateResult generate(@RequestBody GenerateRequest request) {
        return service.generate(request);
    }

    /** 요청 값이 잘못됐다 (빈 과목 · 범위를 벗어난 단계 등). */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    /** 모델 호출 자체가 실패했다. */
    @ExceptionHandler(ModelCallException.class)
    ResponseEntity<Map<String, String>> badGateway(ModelCallException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "AI 호출에 실패했습니다. 잠시 뒤 다시 시도해 주세요."));
    }
}
