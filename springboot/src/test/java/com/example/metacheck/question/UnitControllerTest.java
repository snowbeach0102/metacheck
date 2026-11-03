package com.example.metacheck.question;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 스프링 컨텍스트 · DB 없이 단원 API 만 확인한다. */
class UnitControllerTest {

    private static MockMvc mvc(ModelClient client) {
        PromptTemplates templates = new PromptTemplates();
        ExamUnits units = new ExamUnits();
        QuestionGenerationService generator = new QuestionGenerationService(new PromptAssembler(templates, new ExampleBank()), client,
                new QuestionVerifier(templates), () -> 0, () -> new Random(1));
        return MockMvcBuilders.standaloneSetup(new UnitController(units, new UnitQuestionService(units, generator))).build();
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void 단원표를_과목_주요항목_단원_순으로_돌려준다() throws Exception {
        mvc(new MockModelClient()).perform(get("/api/v1/ai/units"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exam").value("정보처리산업기사"))
                .andExpect(jsonPath("$.subjects.length()").value(3))
                .andExpect(jsonPath("$.subjects[0].items[0].units[0].id").value("sys-os"))
                .andExpect(jsonPath("$.subjects[0].items[0].units[0].bankId").value("os"))
                .andExpect(jsonPath("$.subjects[2].items[1].units[1].name").value("고급 SQL 작성"))
                .andExpect(jsonPath("$.subjects[2].items[1].units[1].concepts.length()").value(3));
    }

    @Test
    void 단원_하나를_id로_돌려주고_없으면_404() throws Exception {
        MockMvc m = mvc(new MockModelClient());
        m.perform(get("/api/v1/ai/units/sys-net")).andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("정보시스템 기반 기술"));
        m.perform(get("/api/v1/ai/units/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 단원_진단_문항을_만든다_본문이_없으면_9문항() throws Exception {
        MockMvc m = mvc(new MockModelClient());
        m.perform(post("/api/v1/ai/units/db-sql-adv/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(9))
                .andExpect(jsonPath("$.passed").value(9))
                .andExpect(jsonPath("$.cells.length()").value(9));
        m.perform(post("/api/v1/ai/units/db-sql-adv/questions").contentType(MediaType.APPLICATION_JSON).content(json("{\"levels\":[2],\"count\":1}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells.length()").value(3))
                .andExpect(jsonPath("$.cells[0].level").value(2))
                .andExpect(jsonPath("$.cells[0].result.questions[0].lv").value(2));
    }

    @Test
    void 잘못된_요청은_400_없는_단원은_404_모델_실패는_502() throws Exception {
        MockMvc m = mvc(new MockModelClient());
        m.perform(post("/api/v1/ai/units/sys-os/questions").contentType(MediaType.APPLICATION_JSON).content(json("{\"levels\":[1,2,3],\"count\":3}")))
                .andExpect(status().isBadRequest());
        m.perform(post("/api/v1/ai/units/nope/questions")).andExpect(status().isNotFound());
        ModelClient failing = request -> {
            throw new ModelCallException("boom", null);
        };
        mvc(failing).perform(post("/api/v1/ai/units/sys-os/questions")).andExpect(status().isBadGateway());
    }
}
