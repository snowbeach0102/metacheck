package com.example.metacheck.config;

import java.util.regex.Pattern;

import org.springframework.core.env.Environment;

/**
 * DB 비밀번호가 설정되지 않았으면 DB에 접속을 시도하기 전에 이유를 알려 주며 시작을 멈춘다.
 * application.properties 의 spring.datasource.password=${DB_PASSWORD} 는 값을 못 찾으면 스프링 부트가
 * 에러 없이 "${DB_PASSWORD}" 글자를 그대로 비밀번호로 써서, 서버에는 "Access denied" 로만 보이기 때문이다.
 *
 * 일반 빈으로 두면 소용이 없다 — JPA(Hibernate)가 먼저 만들어지며 접속을 시도한다.
 * 그래서 MetacheckApplication.main 이 "환경 설정이 준비된 직후" 시점에 {@link #verify} 를 부른다.
 */
public final class DatabaseSettingsCheck {

    // 못 찾은 ${이름} 이 값에 그대로 남아 있는 모양
    private static final Pattern UNRESOLVED = Pattern.compile("\\$\\{[^}]+}");

    private DatabaseSettingsCheck() {
    }

    public static void verify(Environment env) {
        String password;
        try {
            password = env.getProperty("spring.datasource.password");   // 엄격한 환경은 여기서 IllegalArgumentException
        } catch (IllegalArgumentException e) {
            throw missing(e);
        }
        if (password != null && UNRESOLVED.matcher(password).find()) {   // 스프링 부트 환경은 글자를 그대로 돌려준다
            throw missing(null);
        }
    }

    private static IllegalStateException missing(Throwable cause) {
        return new IllegalStateException("DB 비밀번호가 설정되지 않았습니다. "
                + "서버: 환경변수 DB_PASSWORD (또는 jar 옆 application.properties 의 spring.datasource.password) / "
                + "내 PC: springboot/config/application.properties (자세한 건 springboot/CLAUDE.md)", cause);
    }
}
