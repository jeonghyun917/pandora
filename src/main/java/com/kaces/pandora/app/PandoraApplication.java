package com.kaces.pandora.app;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.kaces.pandora")
@ConfigurationPropertiesScan(basePackages = "com.kaces.pandora")
@MapperScan(basePackages = "com.kaces.pandora", annotationClass = Mapper.class)
@EnableScheduling
public class PandoraApplication {

	// 메소드 설명: main 처리 흐름을 수행합니다.
	public static void main(String[] args) {
		SpringApplication.run(PandoraApplication.class, args);
	}

}
