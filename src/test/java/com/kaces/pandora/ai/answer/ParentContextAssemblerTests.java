package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParentContextAssemblerTests {

	private final ParentContextAssembler assembler = new ParentContextAssembler();

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
	void preservesBodyHeadingAndNumberedListBoundariesForSourceScope() {
		LawSemanticChunkRow child = chunk(12, "p.2 검토 대상",
			"국가정보원 검토 대상에 관한 앞 문단입니다.\n문화체육관광부 검토 대상\n"
				+ "1. 홈페이지 및 웹메일 등 웹기반 정보시스템 구축\n2. 인터넷전화시스템 구축");
		LawAiAnswerGround ground = assembler.toGrounds(List.of(child), Map.of(), Map.of(), row -> "검토 대상").get(0);
		assertThat(new ClaimEvidenceAtomizer().atomizeSource(ground.matchedChildText(), ground.chunkTitle()))
			.contains("문화체육관광부 검토 대상: 홈페이지 및 웹메일 등 웹기반 정보시스템 구축");
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
