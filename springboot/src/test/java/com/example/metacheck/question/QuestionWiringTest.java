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
        ctx.register(PromptTemplates.class, ExampleBank.class, PromptAssembler.class, MockModelClient.class, QuestionGenerationService.class);
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
        }
    }

    @Test
    void metacheck_ai_client_가_mock_이_아니면_가짜_모델은_빠진다() {
        // 실제 Bedrock 구현체를 더하기 전까지는 모델이 없어 앱이 시작되지 않는다 — 가짜 응답이 운영에 섞여 나가지 않는다
        assertThatThrownBy(() -> context(Map.of("metacheck.ai.client", "bedrock")))
                .hasMessageContaining("ModelClient");
    }
}
