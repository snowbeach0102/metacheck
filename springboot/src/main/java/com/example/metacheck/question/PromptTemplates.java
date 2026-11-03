package com.example.metacheck.question;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 프롬프트 고정 조각. 글은 사양서(AI_출제_사양서.pdf)의 템플릿을 그대로 뽑아 resources/prompts 에 둔 것이다.
 * 사양서를 고치면 이 파일들도 같이 고쳐야 한다 (ai관련/소스 README 의 "프롬프트 다시 조립하기" 참고).
 */
@Component
public class PromptTemplates {

    /** 수험생의 시험 — 11/03 범위 축소로 하나(정보처리산업기사). 요청의 examName 대신 이 값을 [시험] 줄에 쓴다. */
    static final String EXAM_NAME = read("exam-name.txt");

    private final String system = read("system.txt");
    private final String personaUniversity = read("persona-university.txt");
    private final String personaCert = read("persona-cert.txt");
    private final String examScope = read("exam-scope.txt");
    private final String outputFormat = read("output-format.txt");
    private final String[] level = {null, read("level-1.txt"), read("level-2.txt"), read("level-3.txt")};
    private final String verifySystem = read("verify-system.txt");
    private final String verifyUser = read("verify-user.txt");
    private final String levelCriteria = read("level-criteria.txt");

    String system() {
        return system;
    }

    /** 수험생 SYSTEM 의 [출제 범위] 블록 (사양서 15쪽 출제기준). */
    String examScope() {
        return examScope;
    }

    /** 게이트 ③ 2차 풀이의 SYSTEM · USER 틀 · {{level_criteria}} 값 (사양서 7 · 9쪽). */
    String verifySystem() {
        return verifySystem;
    }

    String verifyUser() {
        return verifyUser;
    }

    String levelCriteria() {
        return levelCriteria;
    }

    String persona(Persona p) {
        return p == Persona.CERT_EXAM ? personaCert : personaUniversity;
    }

    String level(int lv) {
        return level[lv];
    }

    String outputFormat() {
        return outputFormat;
    }

    private static String read(String name) {
        try (InputStream in = new ClassPathResource("prompts/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        } catch (IOException e) {
            throw new UncheckedIOException("프롬프트 조각을 읽지 못했습니다: " + name, e);
        }
    }
}
