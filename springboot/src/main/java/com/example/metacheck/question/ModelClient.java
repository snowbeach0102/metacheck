package com.example.metacheck.question;

import java.util.List;

/**
 * 생성 모델 호출부. 구현체는 설정 metacheck.ai.client 로 고른다:
 * {@link MockModelClient}(가짜 응답, 기본값) · {@link BedrockModelClient}(실제 호출, 비용 발생).
 */
@FunctionalInterface
public interface ModelClient {

    Reply invoke(Request request);

    /** 모델 등급. 실제 모델 ID 는 구현체의 설정에 있다. */
    enum Tier {
        /** 경량(Haiku급): 기초 · 실전 단계 */
        BASIC,
        /** 상위(Sonnet급): 심화 단계 */
        ADVANCED
    }

    /** role 은 "user" 또는 "assistant". */
    record Turn(String role, String text) {
    }

    record Request(String system, List<Turn> messages, int maxTokens, double temperature, Tier tier) {
    }

    record Reply(String text, String stopReason) {
    }
}
