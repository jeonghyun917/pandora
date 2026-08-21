package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentDiscoveryPolicyTests {

	@Test
	void lawDiscoveryOrdersLawBeforeAdministrativeRulesAndGuides() {
		List<LawAiAnswerGround> ordered = DocumentDiscoveryPolicy.orderGrounds(
			"CCTV 관련 법령",
			List.of(
				ground(1, 11, "official_doc", "CCTV 설치 운영 가이드", 0.98),
				ground(2, 12, "admrul", "표준 개인정보 보호지침", 0.92),
				ground(3, 13, "law", "개인정보 보호법", 0.70)
			)
		);

		assertThat(ordered).extracting(LawAiAnswerGround::target)
			.containsExactly("law", "admrul", "official_doc");
		assertThat(ordered).extracting(LawAiAnswerGround::number)
			.containsExactly(1, 2, 3);
	}

	@Test
	void regulationAndGuideDiscoveryUseTheirRequestedSourceType() {
		List<LawAiAnswerGround> grounds = List.of(
			ground(1, 21, "law", "개인정보 보호법", 0.91),
			ground(2, 22, "admrul", "표준 개인정보 보호지침", 0.74),
			ground(3, 23, "official_doc", "CCTV 설치 운영 가이드", 0.70),
			ground(4, 24, "internal_doc", "CCTV 운영 매뉴얼", 0.88)
		);

		assertThat(DocumentDiscoveryPolicy.orderGrounds("CCTV 관련 규정", grounds))
			.extracting(LawAiAnswerGround::target)
			.containsExactly("admrul", "law", "official_doc", "internal_doc");
		assertThat(DocumentDiscoveryPolicy.orderGrounds("CCTV 관련 가이드", grounds))
			.extracting(LawAiAnswerGround::target)
			.containsExactly("official_doc", "internal_doc", "law", "admrul");
	}

	@Test
	void substantiveQuestionsKeepTheExistingGroundOrderAndNumbers() {
		List<LawAiAnswerGround> grounds = List.of(
			ground(4, 31, "official_doc", "CCTV 설치 운영 가이드", 0.98),
			ground(7, 32, "law", "개인정보 보호법", 0.70)
		);

		assertThat(DocumentDiscoveryPolicy.orderGrounds(
			"CCTV 관련 법령상 설치 조건은?",
			grounds
		)).extracting(LawAiAnswerGround::target, LawAiAnswerGround::number)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple("official_doc", 4),
				org.assertj.core.groups.Tuple.tuple("law", 7)
			);
	}

	@Test
	void lawDiscoveryPreservesOneBestChunkPerDocumentInSourcePriorityOrder() {
		LawSemanticChunkRow guide = chunk(101, 11, "official_doc", "CCTV 설치 운영 가이드");
		LawSemanticChunkRow rule = chunk(201, 12, "admrul", "표준 개인정보 보호지침");
		LawSemanticChunkRow lawLower = chunk(301, 13, "law", "개인정보 보호법");
		LawSemanticChunkRow lawBest = chunk(302, 13, "law", "개인정보 보호법");

		List<LawSemanticChunkRow> ordered = DocumentDiscoveryPolicy.orderChunks(
			"CCTV 관련 법령",
			List.of(guide, rule, lawLower, lawBest),
			Map.of(
				"official_doc:101", 9.0,
				"admrul:201", 8.0,
				"law:301", 2.0,
				"law:302", 3.0
			)
		);

		assertThat(ordered).extracting(LawSemanticChunkRow::chunkId)
			.containsExactly(302L, 201L, 101L);
	}

	@Test
	void discoveryPreservesConfiguredEntityMatchesFromLexicalHeadingsButNotBodyOnlyMatches() {
		LawSemanticChunkRow judgedAnimalLaw = chunk(
			401,
			21,
			"law",
			"동물보호법",
			"제87조(고정형 영상정보처리기기의 설치 등)",
			"동물보호를 위한 설치 기준"
		);
		LawSemanticChunkRow privacyLaw = chunk(
			402,
			22,
			"law",
			"개인정보 보호법",
			"제25조(고정형 영상정보처리기기의 설치·운영 제한)",
			"공개된 장소에서의 설치·운영 제한"
		);
		LawSemanticChunkRow bodyOnlyNoise = chunk(
			403,
			23,
			"law",
			"시설물 안전법",
			"제1조(목적)",
			"참고사항에서 고정형 영상정보처리기기를 언급한다"
		);

		List<LawSemanticChunkRow> preserved = DocumentDiscoveryPolicy.preserveHeadingCandidates(
			"CCTV 관련 법령",
			List.of(judgedAnimalLaw),
			List.of(privacyLaw, bodyOnlyNoise)
		);

		assertThat(preserved).containsExactly(privacyLaw, judgedAnimalLaw);
		assertThat(preserved).doesNotContain(bodyOnlyNoise);
	}

	private LawAiAnswerGround ground(
		int number,
		long documentId,
		String target,
		String title,
		double score
	) {
		return new LawAiAnswerGround(
			number,
			documentId * 10,
			documentId,
			target,
			title,
			"개인정보보호위원회",
			"테스트",
			"20260101",
			"CURRENT",
			"제1조",
			"목적",
			null,
			"CCTV 관련 근거",
			null,
			null,
			score
		);
	}

	private LawSemanticChunkRow chunk(long chunkId, long documentId, String target, String title) {
		return chunk(chunkId, documentId, target, title, "목적", "CCTV 관련 근거");
	}

	private LawSemanticChunkRow chunk(
		long chunkId,
		long documentId,
		String target,
		String title,
		String chunkTitle,
		String text
	) {
		return new LawSemanticChunkRow(
			chunkId,
			documentId,
			target,
			String.valueOf(documentId),
			title,
			"테스트 기관",
			"테스트",
			"20260101",
			"CURRENT",
			"제1조",
			chunkTitle,
			text,
			null,
			null,
			null,
			1,
			"hash-" + chunkId,
			"목적",
			"provision"
		);
	}
}
