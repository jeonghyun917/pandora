package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RagRecoveryMapper {
    Long lockDocument(@Param("documentId") long documentId);
    Long lockDocumentByHash(@Param("hash") String hash);
    List<Long> lockDocumentsByPaths(@Param("paths") List<String> paths);
    Long findChunkDocument(@Param("chunkId") long chunkId);
    Long lockChunkDocument(@Param("chunkId") long chunkId);
    RecoveryRoutingEpoch findRoutingEpoch();
    List<RecoverySnapshot> listRoutingPointers();
    List<String> listActiveBaselines();
    int insertBaseline(@Param("documentId") long documentId,@Param("generationId") String generationId);
    RecoveryAssets findAssets(@Param("generationId") String generationId);
    int insertAssets(@Param("assets") RecoveryAssets assets);
    int markIndexReady(@Param("expected") RecoveryGeneration expected);
    int changedPointerCount();
    int markEmbeddingUncertain(@Param("generationId") String generationId,@Param("chunkIds") List<Long> chunkIds);
    int markEmbeddingsVerified(@Param("generationId") String generationId);
    int verifiedEmbeddingCount(@Param("generationId") String generationId);
    List<RecoveryEmbeddingReceipt> findEmbeddingReceipts(@Param("generationId") String generationId);
    int insertEmbeddingReceipt(@Param("receipt") RecoveryEmbeddingReceipt receipt);
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
