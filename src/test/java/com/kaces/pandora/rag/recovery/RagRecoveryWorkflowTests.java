package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class RagRecoveryWorkflowTests {
    @Test void closedReleaseGateRejectsEveryMutationBeforeAnyDependency() {
        var candidate=mock(RagRecoveryCandidateService.class);var validation=mock(RagRecoveryValidationService.class);
        var index=mock(RagRecoveryIndexService.class);var promotion=mock(RagRecoveryPromotionService.class);
        var rollback=mock(RagRecoveryRollbackService.class);var baseline=mock(RagRecoveryBaselineService.class);var mapper=mock(RagRecoveryMapper.class);
        var workflow=new RagRecoveryWorkflowService(candidate,validation,index,promotion,rollback,baseline,mapper,"a".repeat(40),false);
        assertThatThrownBy(()->workflow.create(42,"a".repeat(64))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->workflow.validate("id")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->workflow.prepare("id")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->workflow.promote("id",0,"a".repeat(64))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->workflow.rollback(42,"id",1,"baseline")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(candidate,validation,index,promotion,rollback,baseline,mapper);
    }
    @Test void previewUsesOnlyServerConfiguredProductIdentity() {
        var candidate=mock(RagRecoveryCandidateService.class);
        var workflow=new RagRecoveryWorkflowService(candidate,null,null,null,null,null,null,"b".repeat(40),false);
        workflow.preview(42);
        verify(candidate).preview(42,"b".repeat(40));
    }
}
