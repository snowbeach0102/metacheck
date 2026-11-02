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

    private final String system = read("system.txt");
    private final String personaUniversity = read("persona-university.txt");
    private final String personaCert = read("persona-cert.txt");
    private final String outputFormat = read("output-format.txt");
    private final String[] level = {null, read("level-1.txt"), read("level-2.txt"), read("level-3.txt")};

    String system() {
        return system;
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
