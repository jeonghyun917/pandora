package com.kaces.pandora.ai.answer;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface LawAiSearchFailureMapper {

	void insertFailure(@Param("failure") LawAiSearchFailureLog failure);

	List<LawAiSearchFailureRow> findRecentFailures(
		@Param("limit") int limit,
		@Param("evalCandidateOnly") boolean evalCandidateOnly,
		@Param("reviewStatus") String reviewStatus
	);

	LawAiSearchFailureRow findById(@Param("failureId") long failureId);

	int markPromoted(
		@Param("failureId") long failureId,
		@Param("caseId") String caseId
	);
}
