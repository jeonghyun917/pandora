package com.kaces.pandora.ai.answer;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.regex.Pattern;

final class AutonomyFollowUpEvidence {
	private static final Pattern REPORT_CLAUSE = Pattern.compile(
		"중앙행정기관의장은[^.!?]{0,40}개선권고요청서[^.!?]{0,16}(?:접수|받은)[^.!?]{0,16}"
			+ "[0-9]+일이내[^.!?]{0,150}반영결과서[^.!?]{0,32}행정안전부장관에게(?:송부|제출|통보)"
	);

	private AutonomyFollowUpEvidence() {}

	static boolean asksDeadline(String query) {
		return query.contains("자치분권") && query.contains("사전협의")
			&& query.contains("개선권고")
			&& (query.contains("반영결과") || query.contains("추가협의"))
			&& (query.contains("언제") || query.contains("기한") || query.contains("며칠") || query.contains("이내"));
	}

	static boolean ownsDeadline(LawSemanticChunkRow chunk) {
		if (!"official_doc".equals(chunk.target()) || chunk.title() == null || chunk.chunkText() == null
			|| !chunk.title().replaceAll("\\s+", "").contains("자치분권사전협의")) {
			return false;
		}
		return REPORT_CLAUSE.matcher(chunk.chunkText().replaceAll("[\\s『』「」]", "")).find();
	}
}
