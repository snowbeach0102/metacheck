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
        PromptTemplates templates = new PromptTemplates();
        QuestionGenerationService service = new QuestionGenerationService(
                new PromptAssembler(templates, new ExampleBank()), client, new QuestionVerifier(templates), () -> 0, () -> new Random(1));
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
                .andExpect(jsonPath("$.questions[0].verify.verified").value(true))   // 2차 풀이를 거쳤다
                .andExpect(jsonPath("$.questions[0].verify.stageOk").value(true))
                .andExpect(jsonPath("$.attempts.length()").value(1));
    }

    @Test
    void 수험생은_요청의_시험명과_상관없이_정보처리산업기사로_낸다() throws Exception {
        // 앱은 시험명을 보내지 않는다(팀 규칙). 다른 시험명을 보내도 서버가 고정한 시험으로 출제한다 (11/03 범위 축소)
        String cert = BODY.replace("\"UNIVERSITY\"", "\"CERT_EXAM\",\"examName\":\"정보처리기사 필기\"");
        java.util.List<ModelClient.Request> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        MockModelClient mock = new MockModelClient();
        mvc(r -> {
            seen.add(r);
            return mock.invoke(r);
        }).perform(post("/api/v1/ai/questions").contentType(MediaType.APPLICATION_JSON).content(cert.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pass"));
        ModelClient.Request first = seen.get(0);
        org.assertj.core.api.Assertions.assertThat(first.messages().get(0).text()).contains("[시험] 정보처리산업기사").doesNotContain("정보처리기사 필기");
        org.assertj.core.api.Assertions.assertThat(first.system()).contains("[출제 범위] 정보처리산업기사");
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
