package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LawAiEvalAttributionTests {
	@Test
	void bindsClaimsByVerificationGroundNumberNotListPosition() {
		assertThat(LawAiEvalResponse.attributeClaims(List.of(link(2)), List.of(ground(2, 91), ground(1, 82))))
			.containsExactly(new LawAiEvalResponse.AttributedClaim(link(2), "law", 23, 91));
	}

	@Test
	void omitsAttributionWhenGroundNumberIsUnknownOrAmbiguous() {
		assertThat(LawAiEvalResponse.attributeClaims(List.of(link(1), link(2), link(0)),
			List.of(ground(1, 82), ground(1, 91), ground(0, 65)))).isEmpty();
	}

	private static ClaimVerifier.ClaimEvidenceLink link(int number) {
		return new ClaimVerifier.ClaimEvidenceLink("claim", "SUPPORTED", number, "source", 3, 1, 1);
	}

	private static LawAiAnswerGround ground(int number, long chunkId) {
		return new LawAiAnswerGround(number, chunkId, 23, "law", "title", "", "", "", "CURRENT",
			"", "", null, "source", null, null, 1);
	}
}
