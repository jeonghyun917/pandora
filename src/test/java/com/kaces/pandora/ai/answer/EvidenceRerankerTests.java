package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import org.junit.jupiter.api.Test;

class EvidenceRerankerTests {

	private final EvidenceReranker reranker = new EvidenceReranker();

	@Test
	void boostsBodyDirectEvidenceOverGeneralScopeContext() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"하드웨어만 사는 사업도 공공SW 과업심의를 해야 해?"
		);
		LawSemanticChunkRow generalScope = chunk(
			1,
			"공공SW사업 법제도 관리감독 및 지원 가이드(2024. 12.)",
			"p.5 대상 사업",
			"대상사업 : 국가기관등의 장이 발주하는 소프트웨어사업. 국가기관, 지방자치단체, 공공기관의 범위를 설명한다."
		);
		LawSemanticChunkRow directExclusion = chunk(
			2,
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"p.5 적용 대상 사업",
			"적용 대상 사업은 국가기관 등이 발주하는 모든 SW사업이다. 단순 H/W(Appliance 포함) 도입·설치, 네트워크 등 인프라 수수료와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상이다."
		);

		double generalScore = reranker.score(generalScope, profile);
		double directScore = reranker.score(directExclusion, profile);

		assertThat(profile.directEvidenceGroups()).isNotEmpty();
		assertThat(directScore).isGreaterThan(generalScore);
	}

	private LawSemanticChunkRow chunk(long id, String title, String chunkTitle, String text) {
		return new LawSemanticChunkRow(
			id,
			1,
			"official_doc",
			String.valueOf(id),
			title,
			"기관",
			"공식 가이드 문서",
			null,
			null,
			"page " + id,
			chunkTitle,
			text,
			(int) id,
			null,
			null,
			(int) id,
			"hash-" + id,
			chunkTitle,
			"target_scope"
		);
	}
}
