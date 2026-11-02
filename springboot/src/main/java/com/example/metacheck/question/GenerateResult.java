package com.example.metacheck.question;

import java.util.List;
import java.util.Map;

/**
 * 문제 생성 결과.
 *
 * @param status     pass(한 번에 통과) · pass-after-retry(재생성 뒤 통과) · partial(요청보다 적게 통과)
 *                   · skipped(근거가 부족해 모델이 만들지 않음) · fail(통과한 문항 없음)
 * @param requested  요청한 문항 수
 * @param attempts   시도별 기록 (최대 2회). errors 는 그 시도에서 걸린 검사 결과
 * @param exampleIds 프롬프트에 쓴 예시 은행 항목 (기록용)
 */
public record GenerateResult(
        String status,
        int requested,
        List<GeneratedQuestion> questions,
        List<Attempt> attempts,
        List<Skipped> skipped,
        List<String> exampleIds) {

    /** 검사를 통과하고 보기를 섞은 문항. distractors 의 키는 섞은 뒤의 보기 번호(정답 제외). */
    public record GeneratedQuestion(
            String c,
            int lv,
            List<String> concepts,
            List<String> steps,
            String q,
            List<String> o,
            int a,
            Map<Integer, String> distractors,
            String x,
            String ev,
            String shuffleMode,
            String shuffleNote) {
    }

    public record Attempt(int n, long ms, List<String> errors) {
    }

    public record Skipped(String c, String reason) {
    }
}
