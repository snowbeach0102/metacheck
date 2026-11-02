package com.example.metacheck.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 스프링 컨텍스트 · DB 없이 설정 점검만 확인한다 (MockEnvironment 는 이 PC의 환경변수를 읽지 않는다). */
class DatabaseSettingsCheckTest {

    private static MockEnvironment env() {
        // application.properties 와 같은 모양: 비밀번호를 환경변수 DB_PASSWORD 에서 가져온다
        return new MockEnvironment().withProperty("spring.datasource.password", "${DB_PASSWORD}");
    }

    @Test
    void 비밀번호를_못_찾으면_이유를_알려_주며_멈춘다() {
        assertThatThrownBy(() -> DatabaseSettingsCheck.verify(env()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB 비밀번호가 설정되지 않았습니다")
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("config/application.properties");
    }

    @Test
    void 스프링_부트처럼_못_찾은_자리표시자를_글자_그대로_돌려주는_환경에서도_멈춘다() {
        // 실제 앱의 환경은 해결 못 한 ${...} 를 에러 없이 그대로 돌려준다 (jar 로 돌려 보고 알게 된 차이)
        MockEnvironment lenient = env();
        lenient.setIgnoreUnresolvableNestedPlaceholders(true);
        assertThat(lenient.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
        assertThatThrownBy(() -> DatabaseSettingsCheck.verify(lenient))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void 환경변수로_주면_통과한다() {
        assertThatCode(() -> DatabaseSettingsCheck.verify(env().withProperty("DB_PASSWORD", "x"))).doesNotThrowAnyException();
    }

    @Test
    void 다른_설정이_비밀번호를_덮어써도_통과한다() {
        // jar 옆 application.properties 나 config/application.properties 가 같은 키를 직접 적은 경우
        MockEnvironment direct = new MockEnvironment().withProperty("spring.datasource.password", "literal-value");
        assertThatCode(() -> DatabaseSettingsCheck.verify(direct)).doesNotThrowAnyException();
        assertThat(direct.getProperty("spring.datasource.password")).isEqualTo("literal-value");
    }
}
