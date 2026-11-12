package com.kaces.pandora.ai.answer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawCommencementSourceRow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A complete commencement clause tied to the official promulgation identity. */
record OfficialCommencementEvidence(LocalDate effectiveDate, String clause, String sourcePath,
	@com.fasterxml.jackson.annotation.JsonIgnore String anchorHash) {
	OfficialCommencementEvidence(LocalDate effectiveDate, String clause) {
		this(effectiveDate, clause, null, null);
	}
	private static final String UNIT_PATH = "$.법령.부칙.부칙단위";
	private static final Pattern HEADING = Pattern.compile("^제[0-9]+조\\s*\\(시행일\\)\\s*\\S");
	private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Pattern HISTORIC_YEAR = Pattern.compile("[0-9]{4}년");
	private static final Pattern NAMED_DATE_QUERY = Pattern.compile("^(.{8,120}?)(?:언제부터효력|언제부터시행|시행일)");

	static boolean isCurrentLookup(String normalized) {
		return (normalized.contains("언제부터효력") || normalized.contains("언제부터시행") || normalized.contains("시행일"))
			&& List.of("최초", "종전", "과거", "당시", "역대").stream().noneMatch(normalized::contains)
			&& !HISTORIC_YEAR.matcher(normalized).find();
	}

	static boolean matchesLookupTitle(String query, String title) {
		if (title.length() < 4 || !isCurrentLookup(query)) return false;
		if (query.contains(title)) return true;
		String ending = "에관한법률";
		if (!title.endsWith(ending)) return false;
		String alias = title.substring(0, title.length() - ending.length()).replace("및", "") + "법";
		var match = NAMED_DATE_QUERY.matcher(query);
		return alias.length() >= 8 && match.find() && List.of("", "은", "는", "이", "가", "의")
			.stream().anyMatch(particle -> match.group(1).equals(alias + particle));
	}

	static Optional<OfficialCommencementEvidence> resolve(
		LawSemanticChunkRow candidate, LawCommencementSourceRow source
	) {
		return resolveBound(candidate, source, false);
	}

	static Optional<OfficialCommencementEvidence> resolveFromDetail(
		LawSemanticChunkRow candidate, LawCommencementSourceRow source
	) {
		return resolveBound(candidate, source, true);
	}

	private static Optional<OfficialCommencementEvidence> resolveBound(
		LawSemanticChunkRow candidate, LawCommencementSourceRow source, boolean detailCitation
	) {
		if (candidate == null || source == null || !"law".equals(candidate.target())
			|| !"CURRENT".equals(candidate.effectiveStatus()) || source.detailId() <= 0
			|| candidate.chunkId() != source.chunkId() || candidate.documentId() != source.documentId()
			|| candidate.externalId() == null || !candidate.externalId().equals(source.externalId())
			|| candidate.chunkVersion() == null || candidate.chunkVersion() != source.chunkVersion()
			|| candidate.sourcePath() == null || !candidate.sourcePath().equals(source.sourcePath())
			|| candidate.chunkText() == null || !candidate.chunkText().equals(source.chunkText())
			|| source.rawJson() == null) {
			return Optional.empty();
		}
		try {
			var detail = JSON.readTree(source.rawJson());
			if (!detailCitation) {
				return resolve(detail, source.effectiveDate(), source.sourcePath(), source.chunkText());
			}
			String title = text(detail.path("법령").path("기본정보").path("법령명_한글"));
			if (title.isBlank() || candidate.title() == null || !compact(title).equals(compact(candidate.title()))) {
				return Optional.empty();
			}
			return resolveOfficialUnit(detail, source.effectiveDate()).map(proof -> new OfficialCommencementEvidence(
				proof.effectiveDate(), proof.clause(), proof.sourcePath(), hash(candidate.chunkText())));
		} catch (tools.jackson.core.JacksonException invalid) {
			return Optional.empty();
		}
	}

	static Optional<OfficialCommencementEvidence> resolve(
		JsonNode detail, String expectedEffectiveDate, String sourcePath, String candidateBody
	) {
		return resolveOfficialUnit(detail, expectedEffectiveDate)
			.filter(proof -> proof.sourcePath().equals(sourcePath) && proof.covers(candidateBody));
	}

	private static Optional<OfficialCommencementEvidence> resolveOfficialUnit(JsonNode detail, String expectedEffectiveDate) {
		if (detail == null) {
			return Optional.empty();
		}
		var law = detail.path("법령");
		var basic = law.path("기본정보");
		String number = text(basic.path("공포번호"));
		String published = text(basic.path("공포일자"));
		String effective = text(basic.path("시행일자"));
		if (!number.matches("[0-9]{1,20}") || !published.matches("[0-9]{8}")
			|| !effective.matches("[0-9]{8}") || !effective.equals(expectedEffectiveDate)) {
			return Optional.empty();
		}
		LocalDate effectiveDate;
		try {
			effectiveDate = LocalDate.parse(effective, DateTimeFormatter.BASIC_ISO_DATE);
			if (effectiveDate.isBefore(LocalDate.parse(published, DateTimeFormatter.BASIC_ISO_DATE))) {
				return Optional.empty();
			}
		} catch (DateTimeParseException invalid) {
			return Optional.empty();
		}
		var units = law.path("부칙").path("부칙단위");
		if (!units.isArray()) {
			return Optional.empty();
		}
		int owner = -1;
		for (int index = 0; index < units.size(); index++) {
			var unit = units.path(index);
			if (number.equals(text(unit.path("부칙공포번호"))) && published.equals(text(unit.path("부칙공포일자")))) {
				if (owner >= 0) {
					return Optional.empty();
				}
				owner = index;
			}
		}
		if (owner < 0) {
			return Optional.empty();
		}
		List<String> clauses = new ArrayList<>();
		for (var lineOrGroup : units.path(owner).path("부칙내용")) {
			if (lineOrGroup.isArray()) {
				for (var line : lineOrGroup) {
					addClause(clauses, line);
				}
			} else {
				addClause(clauses, lineOrGroup);
			}
		}
		if (clauses.size() != 1) {
			return Optional.empty();
		}
		return Optional.of(new OfficialCommencementEvidence(effectiveDate, clauses.get(0),
			UNIT_PATH + "[" + owner + "]", null));
	}

	private static void addClause(List<String> clauses, JsonNode line) {
		String value = text(line).strip();
		if (HEADING.matcher(value).find()) {
			clauses.add(value);
		}
	}

	private static String text(JsonNode node) {
		return node.isTextual() ? node.asText() : "";
	}

	private static String compact(String text) {
		return WHITESPACE.matcher(text).replaceAll("");
	}

	boolean covers(String body) {
		return body != null && (anchorHash == null ? compact(body).contains(compact(clause)) : anchorHash.equals(hash(body)));
	}

	Optional<OfficialCommencementEvidence> projectCitation(String exactBody) {
		if (sourcePath == null || !covers(exactBody)) return Optional.empty();
		return Optional.of(new OfficialCommencementEvidence(effectiveDate, clause, sourcePath, hash(clause)));
	}

	private static String hash(String value) {
		return com.kaces.pandora.common.text.LawHashUtils.sha256(value);
	}

	String officialDateStatement(String title) {
		return "%s 시행일은 %d년 %d월 %d일이다.".formatted(title,
			effectiveDate.getYear(), effectiveDate.getMonthValue(), effectiveDate.getDayOfMonth());
	}
}
