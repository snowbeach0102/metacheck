package com.example.metacheck.question;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 사양서 4장의 SYSTEM · USER 프롬프트를 조립한다 (ai관련/소스/assemble-bedrock.js 의 build 와 같은 규칙).
 * SYSTEM = 고정 템플릿 + 역할 사양 + (단계 사양 + 역할별 형식) + 예시 2개 + 출력 형식, USER = 과목 · 개념 · 단계 줄.
 */
@Component
public class PromptAssembler {

    static final int EXAMPLES = 2;
    private static final String[] LEVEL_LABEL = {null, "기초", "실전", "심화"};

    // 단계 사양 뒤에 붙는 [역할별 형식] 한 줄 (사양서 3장)
    private static final String[] FORMAT_UNIVERSITY = {null,
            "정의 · 특징을 직접 묻기 — 예) \"~의 정의로 가장 알맞은 것은?\"",
            "사례에 개념 적용 · 유사 개념 구분 — 예) \"다음 상황에서 ~는 얼마인가?\"",
            "복합 사례에서 여러 개념을 연결해 풀이 — 예) \"다음 사례에 대한 올바른 판단은?\""};
    private static final String[] FORMAT_CERT = {null,
            "정의 · 구성요소를 문장형으로 — 예) \"~에 대한 설명으로 옳은 것은?\"",
            "유사 개념 · 조건을 기출 문장형으로 구분 — 예) \"다음 중 옳지 않은 것은?\"",
            "복합 사례의 판단과 근거 고르기 — 예) \"옳은 판단과 근거를 고른 것은?\""};

    /** 조립 결과. exampleIds 는 쓰인 예시 은행 항목 id (기록용). */
    public record Prompt(String system, String user, List<String> exampleIds, boolean exampleFallback) {
    }

    private final PromptTemplates templates;
    private final ExampleBank bank;

    public PromptAssembler(PromptTemplates templates, ExampleBank bank) {
        this.templates = templates;
        this.bank = bank;
    }

    /** seed 는 예시를 돌려 쓰는 기준(같은 seed 면 같은 예시). */
    public Prompt build(PromptInput in, int seed) {
        String[] formats = in.persona() == Persona.CERT_EXAM ? FORMAT_CERT : FORMAT_UNIVERSITY;
        String levelSpec = templates.level(in.level()) + "\n[역할별 형식] " + formats[in.level()];

        List<String> names = new ArrayList<>();
        names.add(in.concept().name());
        in.related().forEach(r -> names.add(r.name()));
        ExampleBank.Picked picked = bank.pick(in.persona(), in.level(), names, in.subject(), seed, EXAMPLES);

        String system = templates.system();
        system = replaceLine(system, "persona_spec", templates.persona(in.persona()));
        system = replaceLine(system, "level_spec", levelSpec);
        system = replaceLine(system, "level_example", exampleText(picked.entries()));
        system = replaceLine(system, "output_format", templates.outputFormat());

        List<String> lines = new ArrayList<>();
        lines.add("[과목] " + in.subject() + "     [단원] " + in.topic());
        if (!in.exam().isEmpty()) {
            lines.add("[시험] " + in.exam());
        }
        lines.add("[개념] " + describe(in.concept()) + "   (개념 id: " + in.concept().id() + ")");
        if (!in.related().isEmpty()) {
            lines.add("[관련 개념] " + in.related().stream().map(PromptAssembler::describe).collect(Collectors.joining(" / ")));
        }
        lines.add("[단계] " + LEVEL_LABEL[in.level()] + " (lv " + in.level() + ")     [문항 수] " + in.count());
        in.previousQuestions().forEach(q -> lines.add("[이미 낸 문항] " + q));
        if (!in.material().isEmpty()) {
            lines.add("<자료>" + in.material() + "</자료>");
        }
        if (!in.userRequest().isEmpty()) {
            lines.add("<사용자 요청>" + in.userRequest() + "</사용자 요청>");
        }
        String user = String.join("\n", lines);

        if (system.contains("{{") || system.contains("}}") || user.contains("{{") || user.contains("}}")) {
            throw new IllegalStateException("채우지 못한 {{변수}}가 프롬프트에 남았습니다.");
        }
        return new Prompt(system, user, picked.entries().stream().map(ExampleBank.Entry::id).toList(), picked.fallback());
    }

    private static String describe(ConceptRef c) {
        return c.desc().isEmpty() ? c.name() : c.name() + " — " + c.desc();
    }

    /** "{{name}}  # 설명" 처럼 변수로 시작하는 줄 전체를 text 로 바꾼다. */
    private static String replaceLine(String template, String name, String text) {
        Pattern p = Pattern.compile("^\\{\\{" + name + "\\}\\}.*$", Pattern.MULTILINE);
        return p.matcher(template).replaceFirst(Matcher.quoteReplacement(text));
    }

    private static String exampleText(List<ExampleBank.Entry> entries) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ObjectNode wrapper = Json.MAPPER.createObjectNode();
            ArrayNode questions = wrapper.putArray("questions");
            questions.add(entries.get(i).item());
            wrapper.putArray("skipped");
            parts.add("(예시 " + (i + 1) + ")\n" + Json.pretty(wrapper));
        }
        return String.join("\n\n", parts);
    }
}
