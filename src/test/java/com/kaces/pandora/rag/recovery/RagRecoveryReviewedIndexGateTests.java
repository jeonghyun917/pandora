package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.rag.document.*;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.lexical.SemanticLexicalMapper;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RagRecoveryReviewedIndexGateTests {
    @Test void unrelatedCorpusChangeDuringEmbeddingStopsBeforeQdrant() {
        var documents=mock(RagDocumentMapper.class);var recovery=mock(RagRecoveryMapper.class);
        var candidates=mock(RagRecoveryCandidateService.class);var receipts=mock(RagRecoveryReceiptService.class);
        var embeddings=mock(OpenAiEmbeddingClient.class);var qdrant=mock(QdrantClient.class);
        var lexical=mock(SemanticLexicalMapper.class);var routing=mock(RagRecoveryRoutingService.class);
        var pdf=mock(RagPdfValidatedSourceReader.class);var transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        String id="11111111-1111-1111-1111-111111111111",prior="22222222-2222-2222-2222-222222222222";
        String sourceHash="a".repeat(64),original="b".repeat(64),base="c".repeat(64),product="d".repeat(40),evidence="e".repeat(64);
        var row=new RagDocumentChunkRow(9,42,4,"1","New guide","Procedure","body","Retained reviewed evidence.","Retained reviewed evidence.",1,
                "$.reviewedPdf.units[0]","https://agency.example/new.pdf",0,"f".repeat(64));
        var rows=List.of(row);
        String manifest=RecoveryCandidatePlan.manifestFor(42,sourceHash,product,List.of(RagRecoverySourceValidator.unassigned(row)));
        var generation=new RecoveryGeneration(id,42,sourceHash,product,base,manifest,"1".repeat(64),"VALIDATED");
        var pointer=new RecoverySnapshot(42,0,prior,original,base);
        var captured=new RecoveryRoutingSnapshot(0,Map.of(42L,pointer),Map.of(),Set.of(prior),null,null);
        var source=new RecoveryPdfSource(id,42,"33333333-3333-3333-3333-333333333333",sourceHash,"2".repeat(64),manifest,"New guide","https://agency.example/new.pdf");
        when(routing.captureInTransaction()).thenReturn(captured);
        when(recovery.lockRoutingEpoch()).thenReturn(new RecoveryRoutingEpoch(0,null,null));
        when(recovery.findGeneration(eq(id),anyBoolean())).thenReturn(generation);
        when(recovery.findPointer(42,true)).thenReturn(pointer);
        when(recovery.findCandidateChunks(id)).thenReturn(rows);
        when(documents.findSourceProofDocument(42,true)).thenReturn(new RagDocumentRow(42,"official_doc","Old guide",null,null,null,null,null,1,
                "old.pdf","preserved",null,original,"application/pdf","https://agency.example/old.pdf","INDEXED"));
        when(candidates.lockedPreview(42,product)).thenReturn(new RagRecoveryCandidateService.Preview(42,original,product,base,"3".repeat(64),"4".repeat(64),1,1));
        when(pdf.find(generation,pointer,rows)).thenReturn(Optional.of(new RagPdfValidatedSourceReader.Verified(source,"5".repeat(64),evidence)));
        when(lexical.findActiveSearchableChunks()).thenReturn(List.of());
        var reservation=new AtomicReference<RecoveryPreparationReceipt>();
        when(recovery.findPreparation(id)).thenAnswer(call->reservation.get());
        when(recovery.insertPreparation(any())).thenAnswer(call->{reservation.set(call.getArgument(0));return 1;});
        when(recovery.changeGenerationState(id,"VALIDATED","STALE")).thenReturn(1);
        var corpusRevision=new AtomicLong();
        when(recovery.findCorpusRevision()).thenAnswer(call->corpusRevision.get());
        when(embeddings.embed(anyList())).thenAnswer(call->{corpusRevision.incrementAndGet();return List.of(List.of(1.0,2.0,3.0));});
        when(receipts.loadReviewedPdf(id,evidence)).thenReturn(Map.of(9L,List.of(1.0,2.0,3.0)));
        when(qdrant.inspectRagRecoveryPoints(anyString(),anyString(),anyString(),anyList(),anyList()))
            .thenThrow(new AssertionError("Qdrant was contacted after the corpus changed"));
        var properties=new LawAiProperties(null,new LawAiProperties.Qdrant("http://127.0.0.1:1","unused-law","unused-rag",3),null,null);
        var service=new RagRecoveryIndexService(documents,recovery,candidates,receipts,embeddings,qdrant,lexical,null,
                transactions,properties,product,routing,pdf);
        assertThatThrownBy(()->service.prepareReviewedPdf(id)).isInstanceOf(RecoveryStaleException.class);
        verifyNoInteractions(qdrant);
        verify(recovery).changeGenerationState(id,"VALIDATED","STALE");
    }
}
