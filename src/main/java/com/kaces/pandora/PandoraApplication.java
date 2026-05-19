package com.kaces.pandora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PandoraApplication {

	public static void main(String[] args) {
		SpringApplication.run(PandoraApplication.class, args);
	}

}
