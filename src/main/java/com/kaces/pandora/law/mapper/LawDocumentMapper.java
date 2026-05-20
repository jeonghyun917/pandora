package com.kaces.pandora.law.mapper;

import java.util.List;
import com.kaces.pandora.law.sync.SearchDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawDocumentMapper {
	int countDocuments(
		@Param("target") String target,
		@Param("query") String query,
		@Param("searchAll") boolean searchAll
	);
	List<LawDocumentRow> searchDocuments(
		@Param("target") String target,
		@Param("query") String query,
		@Param("searchAll") boolean searchAll,
		@Param("limit") int limit,
		@Param("offset") int offset
	);
	void upsertDocument(@Param("document") SearchDocument document, @Param("contentHash") String contentHash);
	long findDocumentId(@Param("target") String target, @Param("externalId") String externalId);
}
