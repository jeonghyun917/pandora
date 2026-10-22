package com.kaces.pandora.semantic.batch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.recovery.RagRecoveryWriteGuard;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LawSemanticBatchRecoveryWriteTests {
    @Test void unmanagedEmbeddingStatusExecutesInsideProtectedCallback() {
        var mapper=mock(RagDocumentMapper.class);var guard=mock(RagRecoveryWriteGuard.class);
        doAnswer(call->{((Runnable)call.getArgument(1)).run();return null;}).when(guard).mutateChunk(eq(7L),any());
        var service=new LawSemanticBatchJobService(null,mapper,null,null,null,null,null,null,null,null);
        service.configureRecoveryWriteGuard(guard);
        ReflectionTestUtils.invokeMethod(service,"upsertEmbeddingStatus","official_doc",7L,"model","qdrant","7","hash","INDEXED",null);
        verify(mapper).upsertEmbeddingStatus(7L,"model","qdrant","7","hash","INDEXED",null);
    }
    @Test void managedVectorWriteIsRejectedBeforeExternalMutation() {
        var q=mock(QdrantClient.class);var mapper=mock(RagDocumentMapper.class);
        var guard=mock(RagRecoveryWriteGuard.class);
        doThrow(new IllegalStateException("managed")).when(guard).mutateDocuments(eq(List.of(1765L)),any());
        var service=new LawSemanticBatchJobService(null,mapper,null,null,null,null,null,q,null,null);
        service.configureRecoveryWriteGuard(guard);
        var chunk=new LawSemanticChunkRow(7L,1765L,"official_doc","1765","title",null,null,null,null,null,
            "title","body",1,null,null,1,"hash","parent","body","PASS");
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"upsertVectors","official_doc",
            List.of(chunk),Map.of(7L,List.of(1.0))))
            .isInstanceOf(IllegalStateException.class).hasMessage("managed");
        verifyNoInteractions(q,mapper);
    }
    @Test void managedEmbeddingStatusCannotMutatePreservedBaseline() {
        var q=mock(QdrantClient.class);var mapper=mock(RagDocumentMapper.class);
        var guard=mock(RagRecoveryWriteGuard.class);
        doThrow(new IllegalStateException("managed")).when(guard).mutateChunk(eq(7L),any());
        var service=new LawSemanticBatchJobService(null,mapper,null,null,null,null,null,q,null,null);
        service.configureRecoveryWriteGuard(guard);
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"upsertEmbeddingStatus","official_doc",
            7L,"model","qdrant","7","hash","INDEXED",null))
            .isInstanceOf(IllegalStateException.class).hasMessage("managed");
        verifyNoInteractions(q,mapper);
    }
}
