package com.kaces.pandora.law.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawSyncHistoryMapper {

	/**
	 * ?숆린???쒖옉 ?대젰??RUNNING ?곹깭濡?湲곕줉?⑸땲??
	 */
	void insertSyncHistory(@Param("target") String target, @Param("requestJson") String requestJson);

	/**
	 * ?꾩옱 DB ?몄뀡??留덉?留?AUTO_INCREMENT 媛믪쓣 議고쉶?⑸땲??
	 */
	long lastInsertId();

	/**
	 * ?숆린???깃났 寃곌낵瑜??대젰???④퉩?덈떎.
	 */
	void markSyncSuccess(@Param("historyId") long historyId, @Param("responseJson") String responseJson);

	/**
	 * ?숆린???ㅽ뙣 ?ъ쑀瑜??대젰???④퉩?덈떎.
	 */
	void markSyncFailure(@Param("historyId") long historyId, @Param("errorMessage") String errorMessage);
}
