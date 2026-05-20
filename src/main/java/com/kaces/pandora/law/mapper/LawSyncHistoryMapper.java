package com.kaces.pandora.law.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawSyncHistoryMapper {
	void insertSyncHistory(@Param("target") String target, @Param("requestJson") String requestJson);
	long lastInsertId();
	void markSyncSuccess(@Param("historyId") long historyId, @Param("responseJson") String responseJson);
	void markSyncFailure(@Param("historyId") long historyId, @Param("errorMessage") String errorMessage);
}
