package com.example.metacheck.question;

import java.util.List;

/**
 * 생성 모델 호출부. 지금은 {@link MockModelClient}(가짜 응답)만 있고,
 * 실제 Bedrock 연결은 이 인터페이스의 구현체 하나를 더하면 된다 (서비스 코드는 그대로).
 */
@FunctionalInterface
public interface ModelClient {

    Reply invoke(Request request);

    /** role 은 "user" 또는 "assistant". */
    record Turn(String role, String text) {
    }

    record Request(String system, List<Turn> messages, int maxTokens, double temperature) {
    }

    record Reply(String text, String stopReason) {
    }
}
