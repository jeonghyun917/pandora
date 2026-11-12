package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawCommencementSourceRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OfficialCommencementEvidenceTests {
	private static final String CURRENT = "제1조(시행일) 이 법은 공포 후 6개월이 경과한 날부터 시행한다. 다만, 제8조는 공포 후 1년이 경과한 날부터 시행한다.";
	private static final String HISTORICAL = "제1조(시행일) 이 법은 공포 후 6개월이 경과한 날부터 시행한다.";
	private static final String PATH = "$.법령.부칙.부칙단위[1]";
	private static final String OLD = unit("17000", "20200101", HISTORICAL);
	private static final String NEW = unit("21000", "20260227", CURRENT);
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void returnsOfficialDateWithTheFullOwnedException() {
		assertThat(resolve(OLD + "," + NEW, "20260828", PATH, CURRENT))
			.hasValueSatisfying(evidence -> {
				assertThat(evidence.effectiveDate()).hasToString("2026-08-28");
				assertThat(evidence.clause()).isEqualTo(CURRENT);
			});
	}

	@Test
	void rejectsAmbiguousOrMismatchedOwnership() {
		assertThat(resolve(NEW + "," + NEW, "20260828", PATH, CURRENT)).isEmpty();
		assertThat(resolve(OLD + "," + unit("21000", "20260226", CURRENT), "20260828", PATH, CURRENT)).isEmpty();
		assertThat(resolve(OLD + "," + NEW, "20260227", PATH, CURRENT)).isEmpty();
		assertThat(resolve(OLD + "," + NEW, "20260828", PATH, HISTORICAL)).isEmpty();
		assertThat(resolve(OLD + "," + NEW, "20260828", "$.법령.부칙.부칙단위[0]", CURRENT)).isEmpty();
	}

	private java.util.Optional<OfficialCommencementEvidence> resolve(String units, String date, String path, String body) {
		return OfficialCommencementEvidence.resolve(mapper.readTree(raw(units)), date, path, body);
	}

	@Test
	void bindsReceiptToTheCapturedChunkVersionAndExactBody() {
		var candidate = new LawSemanticChunkRow(41, 12, "law", "60", "예시법", null, null, null,
			"CURRENT", null, "시행일", CURRENT, null, PATH, null, 0, null, null, null, "PASS", null, null, 2);
		String raw = raw(OLD + "," + NEW);
		assertThat(OfficialCommencementEvidence.resolve(candidate,
			new LawCommencementSourceRow(41,12,4,"60",2,PATH,CURRENT,"20260828",raw))).isPresent();
		assertThat(OfficialCommencementEvidence.resolve(candidate,
			new LawCommencementSourceRow(41,12,4,"60",1,PATH,CURRENT,"20260828",raw))).isEmpty();
		assertThat(OfficialCommencementEvidence.resolve(candidate,
			new LawCommencementSourceRow(41,12,4,"60",2,PATH,HISTORICAL,"20260828",raw))).isEmpty();
		assertThat(OfficialCommencementEvidence.resolve(candidate,
			new LawCommencementSourceRow(41,12,4,"60",2,PATH,CURRENT,"20260828","{"))).isEmpty();
	}

	private static String raw(String units) {
		return """
			{"법령":{"기본정보":{"공포번호":"21000","공포일자":"20260227","시행일자":"20260828"},
			"부칙":{"부칙단위":[%s]}}}
			""".formatted(units);
	}

	@Test
	void citesTheStoredDetailWithoutPretendingTheAnchorContainsTheClause() {
		String title = "예시법";
		String intro = "제1조(목적) 이 법은 공공행정의 원칙을 정한다.";
		String anchorPath = "$.법령.조문.조문단위[0]";
		var anchor = new LawSemanticChunkRow(41, 12, "law", "60", title, null, null, null,
			"CURRENT", null, "목적", intro, null, anchorPath, null, 0, null, null, null, "PASS", null, null, 2);
		String detail = raw(OLD + "," + NEW).replace("\"공포번호\":\"21000\"", "\"법령명_한글\":\"예시법\",\"공포번호\":\"21000\"");
		var row = new LawCommencementSourceRow(41,12,4,"60",2,anchorPath,intro,"20260828",detail);
		assertThat(OfficialCommencementEvidence.resolveFromDetail(anchor,row)).hasValueSatisfying(proof -> {
			assertThat(proof.clause()).isEqualTo(CURRENT);
			assertThat(proof.sourcePath()).isEqualTo(PATH);
			assertThat(proof.covers(intro)).isTrue();
			assertThat(proof.covers(HISTORICAL)).isFalse();
			var citation = new LawAiAnswerGround(1,41,12,"law",title,null,null,null,"CURRENT",
				null,"목적",null,intro,anchorPath,null,1.0).withOfficialCommencement(proof);
			assertThat(citation.snippet()).isEqualTo(CURRENT);
			assertThat(citation.sourcePath()).isEqualTo(PATH);
			assertThat(citation.contextChunkIds()).isEmpty();
		});
		assertThat(OfficialCommencementEvidence.resolveFromDetail(anchor,
			new LawCommencementSourceRow(41,12,4,"61",2,anchorPath,intro,"20260828",detail))).isEmpty();
	}

	@Test
	void assembledCitationUsesBoundOfficialClauseInsteadOfTheCleanedAnchor() {
		String title = "예시법";
		String intro = "제1조(목적)  이 법은 공공행정의 원칙을 정한다.\n" + "설명 ".repeat(410);
		String path = "$.법령.조문.조문단위[0]";
		var anchor = new LawSemanticChunkRow(41,12,"law","60",title,null,null,null,"CURRENT",null,
			"목적",intro,null,path,null,0,null,null,null,"PASS",null,null,2);
		String detail = raw(OLD + "," + NEW).replace("\"공포번호\":\"21000\"", "\"법령명_한글\":\"예시법\",\"공포번호\":\"21000\"");
		var proof = OfficialCommencementEvidence.resolveFromDetail(anchor,
			new LawCommencementSourceRow(41,12,4,"60",2,path,intro,"20260828",detail)).orElseThrow();
		var ground = new ParentContextAssembler().toGrounds(List.of(anchor), java.util.Map.of("law:41",anchor),
			java.util.Map.of(), ignored -> "표시용 본문").get(0);
		assertThat(proof.covers(ground.matchedChildText())).isFalse();
		var changed = new LawSemanticChunkRow(41,12,"law","60",title,null,null,null,"CURRENT",null,
			"목적",intro+" 변경",null,path,null,0,null,null,null,"PASS",null,null,2);
		assertThat(ground.withOfficialCommencement(proof,changed).officialCommencement()).isNull();
		var citation = ground.withOfficialCommencement(proof,anchor);
		assertThat(citation.sourcePath()).isEqualTo(PATH);
		assertThat(citation.snippet()).isEqualTo(CURRENT);
		assertThat(citation.matchedChildText()).isEqualTo(CURRENT);
		var verifier = new ClaimVerifier();
		assertThat(verifier.verifyDetailed("예시법 시행일은 2026년 8월 28일이다.",List.of(citation)).unsupportedClaims()).isEmpty();
		assertThat(verifier.verifyDetailed("예시법 시행일은 2026년 2월 27일이다.",List.of(citation)).unsupportedClaims()).hasSize(1);
	}

	private static String unit(String no, String date, String clause) {
		return """
			{"부칙공포번호":"%s","부칙공포일자":"%s","부칙내용":[["부칙","%s"]]}
			""".formatted(no, date, clause);
	}
}
