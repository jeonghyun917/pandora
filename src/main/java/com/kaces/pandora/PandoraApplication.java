package com.kaces.pandora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PandoraApplication {

	/**
	 * 스프링 부트 애플리케이션을 시작하고 설정 프로퍼티 스캔을 활성화합니다.
	 */
	public static void main(String[] args) {
		// 스프링 컨테이너를 생성하고 내장 웹 서버를 기동합니다.
		SpringApplication.run(PandoraApplication.class, args);
	}

}
