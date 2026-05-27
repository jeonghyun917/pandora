package com.kaces.pandora.lawdata.parser;

import java.util.List;
import com.kaces.pandora.lawdata.detail.LawDetailSectionResponse;
import tools.jackson.databind.JsonNode;

public interface StoredDetailSectionParser {
	
	boolean supports(JsonNode root);
	
	List<LawDetailSectionResponse> parse(JsonNode root);
}
