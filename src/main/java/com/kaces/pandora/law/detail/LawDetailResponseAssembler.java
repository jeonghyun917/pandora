package com.kaces.pandora.law.detail;

import static com.kaces.pandora.law.common.LawJsonNodes.child;
import static com.kaces.pandora.law.common.LawJsonNodes.nodes;
import static com.kaces.pandora.law.common.LawJsonNodes.text;
import static com.kaces.pandora.law.common.LawTextUtils.firstNonBlank;
import static com.kaces.pandora.law.common.LawTextUtils.formatDate;

import com.kaces.pandora.law.mapper.LawDetailRow;
import com.kaces.pandora.law.parser.StoredDetailSectionReader;
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

	public LawDetailResponseAssembler(StoredDetailSectionReader sectionReader, ObjectMapper objectMapper) {
		this.sectionReader = sectionReader;
		this.objectMapper = objectMapper;
	}

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

	private List<String> readDetailMeta(LawDetailRow detail) {
		List<String> meta = new ArrayList<>();
		if (StringUtils.hasText(detail.rawJson())) {
			try {
				JsonNode basic = child(child(objectMapper.readTree(detail.rawJson()), "법령"), "기본정보");
				if (basic != null) {
					addMeta(meta, "시행 " + formatDate(text(basic, "시행일자", "")));
					addMeta(meta, lawRevisionText(basic));
					for (JsonNode department : nodes(child(child(basic, "연락부서"), "부서단위"))) {
						String agency = text(department, "소관부처명", "");
						String name = text(department, "부서명", "");
						String phone = text(department, "부서연락처", "");
						addMeta(meta, agency + (StringUtils.hasText(name) ? " (" + name + ")" : "") + (StringUtils.hasText(phone) ? " " + phone : ""));
					}
				}
			} catch (Exception ignored) {
			}
		}
		if (meta.isEmpty()) {
			addMeta(meta, detail.agencyName());
			addMeta(meta, formatDate(detail.sourceDate()));
		}
		return meta;
	}

	private String lawRevisionText(JsonNode basic) {
		String lawType = text(child(basic, "법종구분"), "content", "");
		String promulgationNo = text(basic, "공포번호", "");
		String promulgationDate = formatDate(text(basic, "공포일자", ""));
		String revisionType = text(basic, "제개정구분", "");
		List<String> parts = new ArrayList<>();
		addMeta(parts, lawType + (StringUtils.hasText(promulgationNo) ? " 제" + promulgationNo + "호" : ""));
		addMeta(parts, promulgationDate);
		addMeta(parts, revisionType);
		return String.join(", ", parts);
	}

	private void addMeta(List<String> meta, String value) {
		if (StringUtils.hasText(value)) {
			meta.add(value.trim());
		}
	}
}
