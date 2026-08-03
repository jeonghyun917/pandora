package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkPreviewApprovalTests {

	@Test
	void fullNormalizedSourceCoverageProducesNoLossAndAStableApprovalToken() {
		List<SyncDetailSection> sections = List.of(new SyncDetailSection(
			"article", "Article 1", "Scope", "Alpha beta gamma.", "$.articles[0]", 1, 1
		));
		List<PlannedLawChunk> planned = List.of(new PlannedLawChunk(
			"article", "Article 1", "Scope", "Alpha beta gamma.", "$.articles[0]"
		));

		ChunkPreviewApproval approval = ChunkPreviewApproval.assess("law", 42L, 7L, "raw-source", sections, planned);

		assertThat(approval.unexplainedLossSpanCount()).isZero();
		assertThat(approval.token()).matches("[0-9a-f]{64}");
		assertThat(ChunkPreviewApproval.assess("law", 42L, 7L, "raw-source", sections, planned).token())
			.isEqualTo(approval.token());
	}

	@Test
	void partialNormalizedSourceLossIsDetectedAsABlockingSpan() {
		List<SyncDetailSection> sections = List.of(new SyncDetailSection(
			"article", "Article 1", "Scope", "Alpha beta gamma.", "$.articles[0]", 1, 1
		));
		List<PlannedLawChunk> planned = List.of(new PlannedLawChunk(
			"article", "Article 1", "Scope", "Alpha beta", "$.articles[0]"
		));

		assertThat(ChunkPreviewApproval.assess("law", 42L, 7L, "raw-source", sections, planned).unexplainedLossSpanCount())
			.isGreaterThan(0);
	}

	@Test
	void approvalTokenChangesWhenTheRawSourceIdentityChanges() {
		List<SyncDetailSection> sections = List.of(new SyncDetailSection("article", "Article 1", "Scope", "Alpha beta", "$.articles[0]", 1, 1));
		List<PlannedLawChunk> planned = List.of(new PlannedLawChunk("article", "Article 1", "Scope", "Alpha beta", "$.articles[0]"));

		assertThat(ChunkPreviewApproval.assess("law", 42L, 7L, "raw-a", sections, planned).token())
			.isNotEqualTo(ChunkPreviewApproval.assess("law", 42L, 7L, "raw-b", sections, planned).token());
	}
}
