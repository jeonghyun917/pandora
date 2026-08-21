package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalAuditTermMatcherTests {

	@Test
	void matchesOneAliasPerGroupAgainstNormalizedChunkBodyAndKeepsGroupIndexes() {
		List<List<String>> groups = List.of(
			List.of("처리 목적 고지", "수집·이용 목적"),
			List.of("보유 기간", "이용기간"),
			List.of("동의 거부권")
		);

		List<RetrievalAuditTermMatcher.GroupMatch> matches =
			RetrievalAuditTermMatcher.matchGroups(
				groups,
				"개인정보의 수집ㆍ이용  목적과 보유기간을 알려야 합니다."
			);

		assertThat(matches)
			.extracting(
				RetrievalAuditTermMatcher.GroupMatch::groupIndex,
				RetrievalAuditTermMatcher.GroupMatch::matchedAlias
			)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(0, "수집·이용 목적"),
				org.assertj.core.groups.Tuple.tuple(1, "보유 기간")
			);
	}

	@Test
	void doesNotInferAGroupThatIsAbsentFromTheChunkBody() {
		List<RetrievalAuditTermMatcher.GroupMatch> matches =
			RetrievalAuditTermMatcher.matchGroups(
				List.of(List.of("주요정보통신기반시설")),
				"민감정보를 처리하는 정보시스템은 검토 대상입니다."
			);

		assertThat(matches).isEmpty();
	}

	@Test
	void ignoresBlankAliasesWithoutChangingStableGroupIndexes() {
		List<RetrievalAuditTermMatcher.GroupMatch> matches =
			RetrievalAuditTermMatcher.matchGroups(
				List.of(
					List.of("", "   "),
					List.of("결과 통보")
				),
				"검토 결과 통보가 완료됐습니다."
			);

		assertThat(matches)
			.extracting(RetrievalAuditTermMatcher.GroupMatch::groupIndex)
			.containsExactly(1);
	}

	@Test
	void rejectsAuditRequestsThatExceedAnyConfiguredBound() {
		List<List<String>> tooManyGroups = new ArrayList<>();
		for (int index = 0; index < 33; index += 1) {
			tooManyGroups.add(List.of("group-" + index));
		}
		List<String> tooManyAliases = new ArrayList<>();
		for (int index = 0; index < 17; index += 1) {
			tooManyAliases.add("alias-" + index);
		}

		assertThatIllegalArgumentException()
			.isThrownBy(() -> RetrievalAuditTermMatcher.matchGroups(tooManyGroups, "body"))
			.withMessageContaining("32");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RetrievalAuditTermMatcher.matchGroups(List.of(tooManyAliases), "body"))
			.withMessageContaining("16");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RetrievalAuditTermMatcher.matchGroups(
				List.of(List.of("가".repeat(161))),
				"body"
			))
			.withMessageContaining("160");
	}
}
