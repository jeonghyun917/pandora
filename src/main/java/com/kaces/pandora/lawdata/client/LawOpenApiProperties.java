package com.kaces.pandora.lawdata.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "law-open-api")

// 메소드 설명: LawOpenApiProperties 처리 흐름을 수행합니다.
public record LawOpenApiProperties(String baseUrl, String oc) {
}
