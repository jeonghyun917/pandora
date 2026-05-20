package com.kaces.pandora.law.parser;

import java.util.List;
import com.kaces.pandora.law.detail.LawDetailSectionResponse;
import tools.jackson.databind.JsonNode;

public interface StoredDetailSectionParser {
	boolean supports(JsonNode root);
	List<LawDetailSectionResponse> parse(JsonNode root);
}
