package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;

class RagPdfWorkflowServiceTests {
    @Test void closedGateNeverReadsInputOrTouchesAssets() {
        var db=mock(JdbcTemplate.class);var tx=mock(PlatformTransactionManager.class);
        var documents=mock(RagDocumentMapper.class);var recovery=mock(RagRecoveryMapper.class);
        var candidates=mock(RagRecoveryCandidateService.class);var baseline=mock(RagRecoveryBaselineService.class);
        var index=mock(RagRecoveryIndexService.class);var promotion=mock(RagRecoveryPromotionService.class);
        var rollback=mock(RagRecoveryRollbackService.class);
        for(boolean globalGate:java.util.List.of(false,true)) {
        var service=new RagPdfWorkflowService(db,tx,documents,recovery,candidates,baseline,index,promotion,rollback,
                "a".repeat(40),globalGate,!globalGate,"","");
        assertThatThrownBy(service::register).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(service::stage).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.create("draft","manifest")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.validate("generation","manifest")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.prepare("generation")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.promote("generation",0,"manifest")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.rollback(42,"generation",0,"baseline")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(db,tx,documents,recovery,candidates,baseline,index,promotion,rollback);
        }
    }
}
