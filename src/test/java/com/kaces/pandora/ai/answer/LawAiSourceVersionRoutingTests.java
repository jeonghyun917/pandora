package com.kaces.pandora.ai.answer;

import static org.mockito.Mockito.*;
import com.kaces.pandora.infra.openai.*;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.recovery.*;
import com.kaces.pandora.lawdata.version.SourceVersionRoutingSnapshot;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.*;
import org.junit.jupiter.api.Test;

class LawAiSourceVersionRoutingTests {
    @Test void answerSourceOnlyActivationCannotUseTheLegacyVectorShortcut() {
        var law=mock(LawChunkMapper.class);var rag=mock(RagDocumentMapper.class);var embedding=mock(OpenAiEmbeddingClient.class);
        var q=mock(QdrantClient.class);var router=mock(RagRecoveryRoutingService.class);
        String id="00000000-0000-0000-0000-000000000001",group="a".repeat(64);
        var route=new SourceVersionRoutingSnapshot.Route(group,1,"admrul","admrul:지침",10,20,Set.of(10L,20L),id,"b".repeat(64),"law_source_"+id.replace("-",""),"c".repeat(64),"d".repeat(64),"model",1,1);
        var snapshot=new RecoveryRoutingSnapshot(1,Map.of(),Map.of(),Set.of(),"e".repeat(64),"recovery_"+id,new SourceVersionRoutingSnapshot(1,Map.of(group,route)));
        when(router.capture()).thenReturn(snapshot);when(embedding.embed(anyList())).thenReturn(List.of(List.of(1.0)));
        var service=new LawAiAnswerService(law,rag,embedding,q,mock(OpenAiAnswerClient.class),new EvidenceJudge(),new AnswerGuard(),new ClaimVerifier(),
            new AnswerVerificationService(new AnswerGuard(),new ClaimVerifier()),new ParentContextAssembler(),new EvidenceCandidateDiversifier(),new FailureLoggingService(null),null,new LawAiProperties(null,null,null,null));
        service.configureRecoveryRouting(router);
        try {
            service.answer(new LawAiAnswerRequest("admrul",List.of("admrul"),"공공기관 지침의 처리 기준은 무엇인가요?",5,false));
            verify(q,atLeastOnce()).searchBalanced(anyList(),eq(List.of("admrul")),anyInt(),anyInt(),eq(snapshot));
            verify(q,never()).searchBalanced(anyList(),anyList(),anyInt(),anyInt());
        } finally {service.shutdownExecutors();}
    }
}
