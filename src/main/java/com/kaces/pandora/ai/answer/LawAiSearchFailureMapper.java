package com.kaces.pandora.ai.answer;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawAiSearchFailureMapper {

	void insertFailure(@Param("failure") LawAiSearchFailureLog failure);

	List<LawAiSearchFailureRow> findRecentFailures(
		@Param("limit") int limit,
		@Param("evalCandidateOnly") boolean evalCandidateOnly,
		@Param("reviewStatus") String reviewStatus
	);

	List<LawAiSearchFailureCandidate> findEvaluationCandidates(
		@Param("limit") int limit,
		@Param("minOccurrences") int minOccurrences,
		@Param("since") LocalDateTime since
	);

	LawAiSearchFailureRow findById(@Param("failureId") long failureId);

	int markPromoted(
		@Param("failureId") long failureId,
		@Param("caseId") String caseId
	);
}
