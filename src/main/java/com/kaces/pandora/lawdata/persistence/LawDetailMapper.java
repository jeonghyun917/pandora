package com.kaces.pandora.lawdata.persistence;


import com.kaces.pandora.lawdata.chunk.LawChunkRebuildRow;
import com.kaces.pandora.lawdata.sync.StoredDetail;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawDetailMapper {
	
	LawDetailRow findDetail(@Param("documentId") long documentId);
	
	void upsertDetail(@Param("detail") StoredDetail detail);
	
	long findDetailId(@Param("documentId") long documentId);
	
	List<LawChunkRebuildRow> findChunkRebuildRows(
		@Param("target") String target,
		@Param("limit") int limit,
		@Param("offset") int offset
	);

	List<LawChunkRebuildRow> findChunkRebuildRowsByDocumentIds(
		@Param("target") String target,
		@Param("documentIds") List<Long> documentIds
	);
}
