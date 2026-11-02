package com.example.metacheck.question;

import java.util.List;

/** 정리가 끝난 출제 요청. 프롬프트 조립은 이 값만 본다. */
public record PromptInput(
        Persona persona,
        String subject,
        String topic,
        String exam,
        ConceptRef concept,
        List<ConceptRef> related,
        int level,
        int count,
        List<String> previousQuestions,
        String material,
        String userRequest) {
}
