package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RagRecoveryMapper {
    List<Map<String,Object>> baselineStorageSnapshot(@Param("documentId") long documentId);
    List<Map<String,Object>> embeddingSnapshot(@Param("documentId") long documentId);
    List<Map<String,Object>> keywordSnapshot(@Param("documentId") long documentId);
    List<Map<String,Object>> keywordTermSnapshot(@Param("documentId") long documentId);
    RecoveryGeneration findByPreviewHash(@Param("documentId") long documentId,@Param("previewHash") String previewHash);
    RecoverySnapshot findPointer(@Param("documentId") long documentId,@Param("lock") boolean lock);
    void insertGeneration(@Param("generation") RecoveryGeneration generation);
    void insertPointer(@Param("snapshot") RecoverySnapshot snapshot);
    int insertMembership(@Param("generationId") String generationId,@Param("chunkId") long chunkId);
    void insertInactiveChunk(@Param("chunk") RagDocumentChunkRow chunk);
    long lastChunkId();
    RecoveryGeneration findGeneration(@Param("generationId") String generationId,@Param("lock") boolean lock);
    List<RagDocumentChunkRow> findCandidateChunks(@Param("generationId") String generationId);
    int saveValidation(@Param("expected") RecoveryGeneration expected,@Param("state") String state,@Param("evidence") String evidence);
}
