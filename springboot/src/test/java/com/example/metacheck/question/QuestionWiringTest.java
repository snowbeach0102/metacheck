package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 스프링이 빈을 실제로 엮는지(생성자 주입 · 설정에 따른 모델 선택)를 본다. DB 없이 필요한 클래스만 등록한다. */
class QuestionWiringTest {

    private static AnnotationConfigApplicationContext context(Map<String, Object> props) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
        ctx.register(PromptTemplates.class, ExampleBank.class, PromptAssembler.class, MockModelClient.class, BedrockModelClient.class,
                QuestionVerifier.class, QuestionGenerationService.class, ExamUnits.class, UnitQuestionService.class);
        ctx.refresh();
        return ctx;
    }

    private static final GenerateRequest REQUEST = new GenerateRequest(Persona.UNIVERSITY, "자료구조", "스택과 큐", null,
            new ConceptRef("c1", "스택", "LIFO"), List.of(new ConceptRef(null, "큐", "FIFO")), 1, 2, null, null, null);

    @Test
    void 설정이_없으면_가짜_모델로_엮여_문항을_만든다() {
        try (AnnotationConfigApplicationContext ctx = context(Map.of())) {
            assertThat(ctx.getBean(ModelClient.class)).isInstanceOf(MockModelClient.class);
            GenerateResult r = ctx.getBean(QuestionGenerationService.class).generate(REQUEST);
            assertThat(r.status()).isEqualTo("pass");
            assertThat(r.questions()).hasSize(2);
            assertThat(r.questions()).allMatch(q -> q.verify() != null && q.verify().verified());   // 2차 풀이는 기본으로 켜져 있다
        }
    }

    @Test
    void 단원_출제_서비스도_엮여_단원_하나로_문항을_만든다() {
        try (AnnotationConfigApplicationContext ctx = context(Map.of())) {
            UnitQuestionService.UnitQuestions r = ctx.getBean(UnitQuestionService.class).generate("sys-test", new UnitQuestionService.UnitRequest(List.of(1), 1, null));
            assertThat(r.passed()).isEqualTo(3);
        }
    }

    @Test
    void metacheck_ai_verify_가_false_면_2차_풀이를_하지_않는다() {
        try (AnnotationConfigApplicationContext ctx = context(Map.of("metacheck.ai.verify", "false"))) {
            GenerateResult r = ctx.getBean(QuestionGenerationService.class).generate(REQUEST);
            assertThat(r.status()).isEqualTo("pass");
            assertThat(r.questions()).allMatch(q -> q.verify() == null);
        }
    }

    @Test
    void bedrock_설정이_다_있으면_가짜_모델_대신_Bedrock으로_엮인다() {
        // 만들기만 하고 호출하지 않으므로 AWS 에 접속하지 않는다
        Map<String, Object> props = Map.of("metacheck.ai.client", "bedrock", "metacheck.ai.bedrock.region", "us-east-1",
                "metacheck.ai.bedrock.basic-model-id", "basic-id", "metacheck.ai.bedrock.advanced-model-id", "advanced-id");
        try (AnnotationConfigApplicationContext ctx = context(props)) {
            assertThat(ctx.getBean(ModelClient.class)).isInstanceOf(BedrockModelClient.class);
        }
    }

    @Test
    void bedrock_을_골랐는데_설정이_빠지면_시작하지_않는다() {
        // 가짜 모델로 몰래 넘어가면 가짜 응답이 운영에 섞여 나간다
        assertThatThrownBy(() -> context(Map.of("metacheck.ai.client", "bedrock", "metacheck.ai.bedrock.region", "us-east-1")))
                .hasStackTraceContaining("Bedrock 설정이 없습니다");
    }

    @Test
    void metacheck_ai_client_가_없는_값이면_시작하지_않는다() {
        assertThatThrownBy(() -> context(Map.of("metacheck.ai.client", "bedrok")))
                .hasMessageContaining("ModelClient");
    }
}
