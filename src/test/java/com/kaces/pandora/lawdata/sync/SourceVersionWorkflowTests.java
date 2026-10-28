package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.kaces.pandora.lawdata.client.LawOpenApiService;
import com.kaces.pandora.lawdata.version.SourceVersionCandidateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class SourceVersionWorkflowTests {
    @Test void closedGateRejectsAllMutationsBeforeDatabaseNetworkOrAssetCalls() {
        var db=mock(JdbcTemplate.class);var store=mock(SourceVersionCandidateStore.class);var api=mock(LawOpenApiService.class);var parser=mock(LawOpenApiPayloadParser.class);
        ObjectProvider<SourceVersionPreparationService> prep=mock(ObjectProvider.class);
        ObjectProvider<SourceVersionTransitionService> transition=mock(ObjectProvider.class);
        var service=new SourceVersionWorkflowService(db,store,api,parser,prep,transition,"a".repeat(40),false,false);
        assertThatThrownBy(()->service.stage(42,0,"123")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.validate("id")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.prepare("id")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.prepareRollback("group",1,42)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.promote("id",0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.rollback("id",1)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(db,store,api,parser,prep,transition);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"law","admrul"})
    void stageUsesOnlyTheExactOfficialSearchIdentityAndServerProduct(String target) {
        var db=mock(JdbcTemplate.class);var store=mock(SourceVersionCandidateStore.class);var api=mock(LawOpenApiService.class);var parser=mock(LawOpenApiPayloadParser.class);
        ObjectProvider<SourceVersionPreparationService> prep=mock(ObjectProvider.class);ObjectProvider<SourceVersionTransitionService> transitions=mock(ObjectProvider.class);
        when(prep.getIfAvailable()).thenReturn(mock(SourceVersionPreparationService.class));when(transitions.getIfAvailable()).thenReturn(mock(SourceVersionTransitionService.class));
        when(db.queryForMap(anyString(),eq(42L))).thenReturn(java.util.Map.of("target",target,"title","성과 지침","canonical_key",target+":성과지침","use_yn","Y","effective_status","CURRENT"));
        when(api.search(target,"성과 지침",1,100)).thenReturn("official search");
        var source=new SearchDocument(target,"123","성과 지침","기관","분류","20261020",target+":성과지침","20261020","CURRENT","https://foreign.invalid/never-use","{\"ID\":\"123\"}");
        when(parser.parseSearchDocuments(target,"official search")).thenReturn(java.util.List.of(source));
        String link="/DRF/lawService.do?target="+target+(target.equals("law")?"&MST=":"&ID=")+"123&type=JSON";
        String raw="{\""+(target.equals("law")?"법령명_한글":"행정규칙명")+"\":\"성과 지침\",\"시행일자\":\"20261020\",\"조문내용\":\"제1조(기준) 원문 기준을 적용한다.\"}";
        when(api.detail(link)).thenReturn(raw);
        var service=new SourceVersionWorkflowService(db,store,api,parser,prep,transitions,"b".repeat(40),true,true);
        service.stage(42,0,"123");
        var saved=org.mockito.ArgumentCaptor.forClass(SearchDocument.class);
        verify(store).stage(eq(42L),eq(0L),saved.capture(),eq(raw),eq("b".repeat(40)));
        assertThat(saved.getValue().externalId()).isEqualTo("123");assertThat(saved.getValue().detailLink()).isEqualTo(link);
        verify(api,never()).detail("https://foreign.invalid/never-use");
    }
    @Test void generatedFallbackIdentityCannotRegisterAnOfficialSourceGroup() {
        var db=mock(JdbcTemplate.class);var store=mock(SourceVersionCandidateStore.class);var api=mock(LawOpenApiService.class);var parser=mock(LawOpenApiPayloadParser.class);
        ObjectProvider<SourceVersionPreparationService> prep=mock(ObjectProvider.class);ObjectProvider<SourceVersionTransitionService> transitions=mock(ObjectProvider.class);
        when(prep.getIfAvailable()).thenReturn(mock(SourceVersionPreparationService.class));when(transitions.getIfAvailable()).thenReturn(mock(SourceVersionTransitionService.class));
        when(db.queryForMap(anyString(),eq(42L))).thenReturn(java.util.Map.of("target","admrul","title","성과 지침","canonical_key","admrul:성과지침","use_yn","Y","effective_status","CURRENT"));
        when(api.search("admrul","성과 지침",1,100)).thenReturn("official search");
        String generated="a".repeat(32);
        when(parser.parseSearchDocuments("admrul","official search")).thenReturn(java.util.List.of(new SearchDocument("admrul",generated,"성과 지침","","","20261020","admrul:성과지침","20261020","CURRENT","","{}")));
        var service=new SourceVersionWorkflowService(db,store,api,parser,prep,transitions,"b".repeat(40),true,true);
        assertThatThrownBy(()->service.stage(42,0,generated)).isInstanceOf(IllegalStateException.class);
        verify(api,never()).detail(anyString());verifyNoInteractions(store);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "{\"행정규칙명\":\"다른 지침\",\"시행일자\":\"20261020\"}",
        "{\"행정규칙명\":\"성과 지침\",\"시행일자\":\"20250101\"}",
        "{\"행정규칙명\":\"성과 지침\",\"시행일자\":\"20261020\",\"행정규칙일련번호\":\"999\"}",
        "{\"조문내용\":\"검색 제목을 본문 소유로 자동 부여하면 안 된다\"}"
    })
    void wrongOrMissingOfficialDetailIdentityCannotBeRelabeledAsTheRequestedVersion(String raw) {
        var db=mock(JdbcTemplate.class);var store=mock(SourceVersionCandidateStore.class);var api=mock(LawOpenApiService.class);var parser=mock(LawOpenApiPayloadParser.class);
        ObjectProvider<SourceVersionPreparationService> prep=mock(ObjectProvider.class);ObjectProvider<SourceVersionTransitionService> transitions=mock(ObjectProvider.class);
        when(prep.getIfAvailable()).thenReturn(mock(SourceVersionPreparationService.class));when(transitions.getIfAvailable()).thenReturn(mock(SourceVersionTransitionService.class));
        when(db.queryForMap(anyString(),eq(42L))).thenReturn(java.util.Map.of("target","admrul","title","성과 지침","canonical_key","admrul:성과지침","use_yn","Y","effective_status","CURRENT"));
        when(api.search("admrul","성과 지침",1,100)).thenReturn("official search");
        when(parser.parseSearchDocuments("admrul","official search")).thenReturn(java.util.List.of(new SearchDocument("admrul","123","성과 지침","","","20261020","admrul:성과지침","20261020","CURRENT","","{\"ID\":\"123\"}")));
        when(api.detail(anyString())).thenReturn(raw);
        var service=new SourceVersionWorkflowService(db,store,api,parser,prep,transitions,"b".repeat(40),true,true);
        assertThatThrownBy(()->service.stage(42,0,"123")).isInstanceOf(IllegalStateException.class);verifyNoInteractions(store);
    }
}
