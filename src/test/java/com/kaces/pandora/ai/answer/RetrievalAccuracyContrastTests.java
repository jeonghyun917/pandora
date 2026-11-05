package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import org.junit.jupiter.api.Test;

class RetrievalAccuracyContrastTests {

	private final EvidenceReranker reranker = new EvidenceReranker();

	@Test
	void reportingActionOutranksIncidentOverview() {
		assertDirectRanksHigher("침해사고로 스미싱 피해가 생기면 어디에 신고해야 하나?",
			"침해사고 2차 피해 방지 보안공지", "피해 신고 절차",
			"모바일 결제 피해가 확인되면 스미싱 문자를 캡처하고 소액결제확인서를 발급받는다. 소액결제확인서를 지참하여 관할 경찰서 사이버수사대 또는 민원실을 방문하여 신고한다.",
			"침해사고 조사 개요", "회원 정보 유출 사고에 대해 민관합동조사단을 구성하고 피해 규모와 사고 원인을 조사한다.");
	}

	@Test
	void requiredRfpContentsOutrankDesignAudit() {
		assertDirectRanksHigher("공공기관 제안요청서 작성할 때 필수요소는 무엇인가?",
			"공공정보화사업 제안요청서 작성 가이드", "제안요청서 기재사항",
			"제안요청서에는 과업내용, 요구사항, 계약조건, 평가요소와 평가방법, 제안서의 규격, 기타 필요한 사항 등을 기술하여야 합니다.",
			"설계단계 감리", "감리법인은 사업자로부터 감리대상사업 설계 산출물을 제출받아 제안요청서의 요구사항 반영 여부를 검토한다.");
	}

	@Test
	void omissionConditionsOutrankImpactDescriptionTemplate() {
		assertDirectRanksHigher("보안성 검토를 생략할 수 있는 조건은 무엇인가?",
			"정보화사업 보안성 검토 지침", "검토 생략",
			"보안성 검토를 거쳐 완료한 정보화사업에 대하여 정보통신망 구성을 변경하지 아니하는 범위 내의 후속운영 및 유지보수는 보안성 검토 절차 이행을 생략할 수 있다.",
			"대상 시스템", "대상시스템의 기밀성이 훼손되었을 경우의 영향을 간략하게 기술한다. 예: 정보유출 시 치명적 악영향, 심각한 영향, 제한적 영향.");
	}

	private void assertDirectRanksHigher(String question, String title, String directTitle,
		String directText, String otherTitle, String otherText) {
		QuestionIntentProfile profile = QuestionIntentProfile.from(question);
		assertThat(reranker.score(chunk(1, title, directTitle, directText), profile))
			.isGreaterThan(reranker.score(chunk(2, title, otherTitle, otherText), profile));
	}

	private LawSemanticChunkRow chunk(long id, String title, String section, String body) {
		return new LawSemanticChunkRow(id, 1, "official_doc", String.valueOf(id), title,
			"기관", "공식 가이드", null, null, "page 1", section, body, 1, null, null,
			(int) id, "hash-" + id, section, "procedure");
	}
}
