package com.example.metacheck.question;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 스프링 컨텍스트 · DB 없이 컨트롤러만 확인한다. */
class QuestionControllerTest {

    private static MockMvc mvc(ModelClient client) {
        QuestionGenerationService service = new QuestionGenerationService(
                new PromptAssembler(new PromptTemplates(), new ExampleBank()), client, () -> 0, () -> new Random(1));
        return MockMvcBuilders.standaloneSetup(new QuestionController(service)).build();
    }

    private static final String BODY = """
            {"persona":"UNIVERSITY","subject":"자료구조","topic":"스택과 큐",
             "concept":{"id":"c1","name":"스택","desc":"후입선출 구조"},
             "related":[{"name":"큐","desc":"선입선출 구조"}],
             "level":2,"count":2}
            """;

    @Test
    void 정상_요청은_200_과_문항을_돌려준다() throws Exception {
        mvc(new MockModelClient()).perform(post("/api/v1/ai/questions").contentType(MediaType.APPLICATION_JSON).content(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pass"))
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.questions.length()").value(2))
                .andExpect(jsonPath("$.questions[0].o.length()").value(4))
                .andExpect(jsonPath("$.questions[0].lv").value(2))
                .andExpect(jsonPath("$.questions[0].distractors.length()").value(3))
                .andExpect(jsonPath("$.attempts.length()").value(1));
    }

    @Test
    void 잘못된_요청은_400_과_이유를_돌려준다() throws Exception {
        String bad = BODY.replace("\"level\":2", "\"level\":9");
        mvc(new MockModelClient()).perform(post("/api/v1/ai/questions").contentType(MediaType.APPLICATION_JSON).content(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("level(단계)은 1~3 이어야 합니다."));
    }

    @Test
    void 모델_호출이_실패하면_502() throws Exception {
        ModelClient failing = request -> {
            throw new ModelCallException("boom", null);
        };
        mvc(failing).perform(post("/api/v1/ai/questions").contentType(MediaType.APPLICATION_JSON).content(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists());
    }
}
