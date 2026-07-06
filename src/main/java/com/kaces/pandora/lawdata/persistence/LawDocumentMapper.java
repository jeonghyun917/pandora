package com.kaces.pandora.lawdata.persistence;

import java.util.List;
import com.kaces.pandora.lawdata.sync.SearchDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawDocumentMapper {
	
	int countDocuments(
		@Param("target") String target,
		@Param("query") String query,
		@Param("searchAll") boolean searchAll,
		@Param("titleOnly") boolean titleOnly,
		@Param("includeFuture") boolean includeFuture
	);
	
	List<LawDocumentRow> searchDocuments(
		@Param("target") String target,
		@Param("query") String query,
		@Param("searchAll") boolean searchAll,
		@Param("titleOnly") boolean titleOnly,
		@Param("includeFuture") boolean includeFuture,
		@Param("limit") int limit,
		@Param("offset") int offset
	);
	
	void upsertDocument(@Param("document") SearchDocument document, @Param("contentHash") String contentHash);
	
	long findDocumentId(@Param("target") String target, @Param("externalId") String externalId);

	LawDocumentSyncState findSyncState(@Param("target") String target, @Param("externalId") String externalId);
}
