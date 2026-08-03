package com.kaces.pandora.lawdata.sync;

import static com.kaces.pandora.common.text.LawHashUtils.sha256;

import com.kaces.pandora.common.text.LawTextUtils;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

record ChunkPreviewApproval(String token, int unexplainedLossSpanCount) {

	static ChunkPreviewApproval assess(
		String target,
		long documentId,
		long detailId,
		String rawSource,
		List<SyncDetailSection> sections,
		List<PlannedLawChunk> plannedChunks
	) {
		String source = normalizedSource(sections);
		String planned = normalizedPlanned(plannedChunks);
		int lossSpanCount = unexplainedLossSpanCount(source, plannedChunks);
		String token = sha256(String.join("\n",
			nullToEmpty(target),
			String.valueOf(documentId),
			String.valueOf(detailId),
			sha256(nullToEmpty(rawSource)),
			sha256(source),
			sha256(planned),
			String.valueOf(lossSpanCount)
		));
		return new ChunkPreviewApproval(token, lossSpanCount);
	}

	private static int unexplainedLossSpanCount(String source, List<PlannedLawChunk> plannedChunks) {
		if (!StringUtils.hasText(source)) {
			return 0;
		}
		boolean[] covered = new boolean[source.length()];
		int cursor = 0;
		for (PlannedLawChunk chunk : plannedChunks == null ? List.<PlannedLawChunk>of() : plannedChunks) {
			String text = normalize(chunk == null ? null : chunk.text());
			if (!StringUtils.hasText(text)) {
				continue;
			}
			int position = source.indexOf(text, Math.max(0, cursor - 160));
			if (position < 0) {
				position = source.indexOf(text);
			}
			if (position < 0) {
				continue;
			}
			for (int index = position; index < position + text.length(); index++) {
				covered[index] = true;
			}
			cursor = Math.max(cursor, position + text.length());
		}
		int spans = 0;
		boolean inLoss = false;
		for (int index = 0; index < source.length(); index++) {
			boolean lostContent = !covered[index] && !Character.isWhitespace(source.charAt(index));
			if (lostContent && !inLoss) {
				spans++;
			}
			inLoss = lostContent;
		}
		return spans;
	}

	private static String normalizedSource(List<SyncDetailSection> sections) {
		List<String> values = new ArrayList<>();
		for (SyncDetailSection section : sections == null ? List.<SyncDetailSection>of() : sections) {
			String text = normalize(section == null ? null : section.body());
			if (StringUtils.hasText(text)) {
				values.add(text);
			}
		}
		return String.join("\n", values);
	}

	private static String normalizedPlanned(List<PlannedLawChunk> chunks) {
		List<String> values = new ArrayList<>();
		for (PlannedLawChunk chunk : chunks == null ? List.<PlannedLawChunk>of() : chunks) {
			String text = normalize(chunk == null ? null : chunk.text());
			if (StringUtils.hasText(text)) {
				values.add(text);
			}
		}
		return String.join("\n", values);
	}

	private static String normalize(String value) {
		return LawTextUtils.normalizeText(LawTextUtils.stripHtmlTags(nullToEmpty(value)));
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
