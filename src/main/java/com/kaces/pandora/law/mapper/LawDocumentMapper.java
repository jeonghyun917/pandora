package com.kaces.pandora.law.mapper;

import java.util.List;
import com.kaces.pandora.law.sync.SearchDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawDocumentMapper {

	/**
	 * 寃??議곌굔??留욌뒗 臾몄꽌 ?섎? 怨꾩궛?⑸땲??
	 */
	int countDocuments(
		@Param("target") String target,
		@Param("query") String query,
		@Param("searchAll") boolean searchAll
	);

	/**
	 * 寃??議곌굔??留욌뒗 臾몄꽌 紐⑸줉???섏씠吏 ?⑥쐞濡?議고쉶?⑸땲??
	 */
	List<LawDocumentRow> searchDocuments(
		@Param("target") String target,
		@Param("query") String query,
		@Param("searchAll") boolean searchAll,
		@Param("limit") int limit,
		@Param("offset") int offset
	);

	/**
	 * 臾몄꽌 紐⑸줉 ?뺣낫瑜??덈줈 ?ｊ굅??湲곗〈 ?됱쓣 理쒖떊 API 媛믪쑝濡?媛깆떊?⑸땲??
	 */
	void upsertDocument(@Param("document") SearchDocument document, @Param("contentHash") String contentHash);

	/**
	 * target怨??먮낯 ID濡??대? 臾몄꽌 ID瑜?李얠뒿?덈떎.
	 */
	long findDocumentId(@Param("target") String target, @Param("externalId") String externalId);
}
