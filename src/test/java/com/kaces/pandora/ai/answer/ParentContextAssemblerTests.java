package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParentContextAssemblerTests {

	private final ParentContextAssembler assembler = new ParentContextAssembler();

	@Test
	void preservesTheSelectedConditionalDutiesOnlyFromTheMatchedChild() {
		String prefix = "별도 절차가 적용된다. ".repeat(100);
		String clause = "사전협의 신청 누락이 있는 경우에는 협의기관의 장은 해당기관의 장에게 이를 통보하고, 해당기관의 장은 즉시 신청하여야 한다.";
		var source = new LawSemanticChunkRow(12L, 1L, "admrul", "test", "규정", "기관", null,
			null, "CURRENT", "제13조", "신청 절차", prefix + clause, null, null, null, 1,
			"hash", "제13조", "article");
		var neighborOnly = chunk(11, "다른 근거", prefix);
		assertThat(assembler.toGrounds(List.of(source), Map.of(), Map.of(), row -> clause)
			.get(0).matchedChildText()).isEqualTo(clause);
		assertThat(assembler.toGrounds(List.of(source), Map.of("admrul:12", neighborOnly), Map.of(), row -> clause)
			.get(0).matchedChildText()).doesNotContain(clause);
	}

	@Test
	void separatesMatchedChildFromExpandedParentContext() {
		LawSemanticChunkRow child = chunk(9, "적용 대상 사업", "국가기관 등이 발주하는 모든 SW사업은 과업심의 대상이다.");
		LawSemanticChunkRow parent = chunk(
			10,
			"과업심의 대상",
			"적용 대상 사업\n국가기관 등이 발주하는 모든 SW사업은 과업심의 대상이다.\n단순 H/W 도입 설치는 비대상이다."
		);

		List<LawAiAnswerGround> grounds = assembler.toGrounds(
			List.of(parent),
			Map.of("official_doc:10", child),
			Map.of("official_doc:9", 0.91),
			chunk -> "적용 대상 사업 — 국가기관 등이 발주하는 모든 SW사업은 과업심의 대상이다."
		);

		assertThat(grounds).hasSize(1);
		LawAiAnswerGround ground = grounds.get(0);
		assertThat(ground.matchedChildText()).contains("모든 SW사업은 과업심의 대상");
		assertThat(ground.parentContextText()).contains("단순 H/W");
		assertThat(ground.contextChunkIds()).containsExactly(9L, 10L);
		assertThat(ground.score()).isEqualTo(0.91);
		assertThat(ground.contextPolicy()).isEqualTo("parent_context_expanded");
	}

	@Test
	void marksGroundAsMatchedChildOnlyWhenTextsAreSame() {
		LawSemanticChunkRow child = chunk(11, "본문", "공익신고자는 비밀보장을 받을 수 있다.");

		List<LawAiAnswerGround> grounds = assembler.toGrounds(
			List.of(child),
			Map.of("law:11", child),
			Map.of(),
			chunk -> "공익신고자는 비밀보장을 받을 수 있다."
		);

		assertThat(grounds.get(0).parentContextText()).isNull();
		assertThat(grounds.get(0).contextPolicy()).isEqualTo("matched_child_only");
	}

	@Test
	void sameIdExpansionDoesNotReplaceMatchedChildEvidence() {
		LawSemanticChunkRow child = chunk(13, "제재 조건", "다음 각 호에 해당하는 경우 참가자격을 제한한다.");
		LawSemanticChunkRow expanded = chunk(13, "제재 조건",
			child.chunkText() + "\n1. 계약 이행에 부정한 행위가 있는 경우\n작업장소는 협의하여 정한다.");
		LawAiAnswerGround ground = assembler.toGrounds(
			List.of(expanded), Map.of("official_doc:13", child), Map.of(), row -> "제재 조건"
		).get(0);

		assertThat(ground.matchedChildText()).isEqualTo("다음 각 호에 해당하는 경우 참가자격을 제한한다.");
		assertThat(ground.matchedChildText()).doesNotContain("부정한 행위", "작업장소");
		assertThat(ground.parentContextText()).contains("1. 계약 이행에 부정한 행위가 있는 경우");
		assertThat(ground.contextPolicy()).isEqualTo("parent_context_expanded");
		// Expansion text alone cannot establish which neighboring chunk supplied it.
		assertThat(ground.contextChunkIds()).containsExactly(13L);
	}

	@Test
	void preservesExplicitNeighborProvenanceWithoutReplacingMatchedText() {
		var child = chunk(13, "제재 조건", "다음 각 호에 해당하는 경우 참가자격을 제한한다.");
		var expanded = chunk(13, "제재 조건", child.chunkText() + "\n1. 계약 이행에 부정한 행위가 있는 경우");
		var ground = assembler.toGrounds(List.of(expanded), Map.of("official_doc:13", child),
			Map.of(), row -> "제재 조건", "direct", Map.of("official_doc:13", List.of(13L, 14L, 14L))).get(0);
		assertThat(ground.matchedChildText()).isEqualTo(child.chunkText());
		assertThat(ground.parentContextText()).contains("부정한 행위");
		assertThat(ground.contextChunkIds()).containsExactly(13L, 14L);
		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> ground.contextChunkIds().add(15L)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ignoresNeighborProvenanceWhenNoExpandedTextWasReturned() {
		var child = chunk(13, "제재 조건", "다음 각 호에 해당하는 경우 참가자격을 제한한다.");
		var ground = assembler.toGrounds(List.of(child), Map.of("official_doc:13", child),
			Map.of(), row -> "제재 조건", "direct", Map.of("official_doc:13", List.of(14L))).get(0);
		assertThat(ground.contextChunkIds()).containsExactly(13L);
		assertThat(ground.parentContextText()).isNull();
		assertThat(ground.contextPolicy()).isEqualTo("matched_child_only");
	}

	@Test
	void preservesBodyHeadingAndNumberedListBoundariesForSourceScope() {
		LawSemanticChunkRow child = chunk(12, "p.2 검토 대상",
			"국가정보원 검토 대상에 관한 앞 문단입니다.\n문화체육관광부 검토 대상\n"
				+ "1. 홈페이지 및 웹메일 등 웹기반 정보시스템 구축\n2. 인터넷전화시스템 구축");
		LawAiAnswerGround ground = assembler.toGrounds(List.of(child), Map.of(), Map.of(), row -> "검토 대상").get(0);
		assertThat(new ClaimEvidenceAtomizer().atomizeSource(ground.matchedChildText(), ground.chunkTitle()))
			.contains("문화체육관광부 검토 대상: 홈페이지 및 웹메일 등 웹기반 정보시스템 구축");
	}

	@Test
	void preservesOnlyTheCompleteNumberedParagraphFromTheMatchedLegalChild() {
		var assembler = new ParentContextAssembler();
		String prefix = "별도 절차가 적용된다. ".repeat(100);
		String paragraph = "⑤ 제안요청서에는 다음 각 호의 사항을 명시하여야 한다.1. 과업내용2. 평가방법";
		String following = " 제6조(입찰공고) 별도의 공고 조건을 명시한다.";
		var legal = chunk(11, "제안요청서", prefix + paragraph + following);
		var ground = assembler.toGrounds(List.of(legal), Map.of("law:11", legal), Map.of(),
			row -> paragraph.substring(2) + following).get(0);
		assertThat(ground.matchedChildText()).isEqualTo(paragraph);

		var incomplete = chunk(11, "제안요청서", prefix + paragraph.replace("2.", "3.") + following);
		assertThat(assembler.toGrounds(List.of(incomplete), Map.of(), Map.of(),
			row -> paragraph.substring(2)).get(0).matchedChildText()).doesNotContain("⑤");
		var unrelated = chunk(11, "다른 조항", prefix);
		assertThat(assembler.toGrounds(List.of(legal), Map.of("law:11", unrelated), Map.of(),
			row -> paragraph.substring(2)).get(0).matchedChildText()).doesNotContain("⑤");
	}

	private LawSemanticChunkRow chunk(long id, String title, String text) {
		return new LawSemanticChunkRow(
			id,
			1,
			id == 11 ? "law" : "official_doc",
			"ext",
			"테스트 문서",
			"기관",
			"공식 가이드 문서",
			null,
			null,
			"원문 1쪽",
			title,
			text,
			1,
			null,
			null,
			1,
			"hash",
			"상위 섹션",
			"target_scope"
		);
	}
}
