package com.kaces.pandora.law;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "law-open-api")
public record LawOpenApiProperties(String baseUrl, String oc) {
}
