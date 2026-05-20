package com.kaces.pandora.law.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 踰뺣졊?쇳꽣 Open API ?몄텧???꾩슂??湲곕낯 URL怨??몄쬆???ㅼ젙???댁뒿?덈떎.
 */
@ConfigurationProperties(prefix = "law-open-api")
public record LawOpenApiProperties(String baseUrl, String oc) {
}
