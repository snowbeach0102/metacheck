package com.example.metacheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;

import com.example.metacheck.config.DatabaseSettingsCheck;

@SpringBootApplication
public class MetacheckApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(MetacheckApplication.class);
		// 설정(환경변수 · config 파일)이 다 읽힌 직후, DB에 접속하기 전에 비밀번호가 있는지 본다
		app.addListeners((ApplicationEnvironmentPreparedEvent e) -> DatabaseSettingsCheck.verify(e.getEnvironment()));
		app.run(args);
	}

}
