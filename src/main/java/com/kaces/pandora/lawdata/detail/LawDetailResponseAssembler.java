package com.kaces.pandora.lawdata.detail;


import com.kaces.pandora.common.json.LawJsonNodes;
import com.kaces.pandora.common.text.LawTextUtils;
import static com.kaces.pandora.common.json.LawJsonNodes.child;
import static com.kaces.pandora.common.json.LawJsonNodes.nodes;
import static com.kaces.pandora.common.json.LawJsonNodes.text;
import static com.kaces.pandora.common.text.LawTextUtils.firstNonBlank;
import static com.kaces.pandora.common.text.LawTextUtils.formatDate;

import com.kaces.pandora.lawdata.persistence.LawDetailRow;
import com.kaces.pandora.lawdata.parser.StoredDetailSectionReader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LawDetailResponseAssembler {

	private final StoredDetailSectionReader sectionReader;
	private final ObjectMapper objectMapper;

	
	// 메소드 설명: LawDetailResponseAssembler 처리 흐름을 수행합니다.
	public LawDetailResponseAssembler(StoredDetailSectionReader sectionReader, ObjectMapper objectMapper) {
		this.sectionReader = sectionReader;
		this.objectMapper = objectMapper;
	}

	
	// 메소드 설명: assemble 처리 흐름을 수행합니다.
	public LawDetailResponse assemble(LawDetailRow detail) {
		return new LawDetailResponse(
			true,
			"DB",
			detail.documentId(),
			firstNonBlank(detail.detailTitle(), detail.title()),
			readDetailMeta(detail),
			
			sectionReader.readSections(detail.rawJson(), detail.sectionsJson())
		);
	}

	
	// 메소드 설명: readDetailMeta 처리 흐름을 수행합니다.
	private List<String> readDetailMeta(LawDetailRow detail) {
		List<String> meta = new ArrayList<>();
		addMeta(meta, detail.agencyName());
		addMeta(meta, formatDate(detail.sourceDate()));
		return meta;
	}
	/**
	 * Adds non-empty metadata values to the response list.
	 */
	// 메소드 설명: addMeta 처리 흐름을 수행합니다.
	private void addMeta(List<String> meta, String value) {
		if (StringUtils.hasText(value)) {
			meta.add(value.trim());
		}
	}
}
