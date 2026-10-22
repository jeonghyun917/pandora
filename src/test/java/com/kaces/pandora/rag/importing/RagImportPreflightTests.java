package com.kaces.pandora.rag.importing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.kaces.pandora.rag.chunk.RagChunker;
import com.kaces.pandora.rag.chunk.RagChunkQualityGate;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.search.RagChunkSearchIndexService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagImportPreflightTests {
    @TempDir Path temp;

    @Test void managedSourceCannotBeReimportedBeforeDocumentUpsert() throws Exception {
        Path file=temp.resolve("managed.txt");
        Files.writeString(file,"자료 처리 가이드\n\n"+
            "기관은 원본 자료를 검토하고 처리 결과를 기록하여야 한다. 담당자는 변경 이력을 보존하고 필요한 경우 원본과 대조하여 확인하여야 한다. ".repeat(8));
        var mapper=mock(RagDocumentMapper.class);
        var guard=mock(com.kaces.pandora.rag.recovery.RagRecoveryWriteGuard.class);
        doThrow(new IllegalStateException("managed")).when(guard).assertMutableHash(anyString());
        var service=new RagImportService(mapper,new RagTextExtractor(),new RagChunker(),new RagChunkQualityGate(),
            mock(RagChunkSearchIndexService.class),null,null,null,null,transactions());
        service.configureRecoveryWriteGuard(guard);
        assertThatThrownBy(()->service.importFile(file,"official_doc",false,true))
            .isInstanceOf(IllegalStateException.class).hasMessage("managed");
        verify(mapper).findDocumentByHash(anyString());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void rejectsAnOriginalFileChangedDuringExtractionBeforeAnyMutation() throws Exception {
        Path file=temp.resolve("changing.txt");Files.writeString(file,"원래 파일");
        var extractor=mock(RagTextExtractor.class);
        when(extractor.extract(file)).thenAnswer(call->{
            Files.writeString(file,"변경된 파일");
            return new ExtractedDocument(List.of(new ExtractedPage(1,"기관은 원본 자료를 검토하고 변경 이력을 보존하여야 한다. ".repeat(20))));
        });
        var mapper=mock(RagDocumentMapper.class);
        when(mapper.findDocumentIdByHash(anyString())).thenReturn(42L);
        when(mapper.findChunkIdsByDocumentId(42L)).thenReturn(List.of());
        var service=new RagImportService(mapper,extractor,new RagChunker(),new RagChunkQualityGate(),
            mock(RagChunkSearchIndexService.class),null,null,null,null,transactions());
        assertThatThrownBy(()->service.importFile(file,"official_doc",false,false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed during extraction");
        verify(mapper,never()).deactivateChunksByDocumentIdAndVersion(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt());
        verify(mapper,never()).insertChunk(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rebindsTableProvenanceWithTheActualOriginalFileHash() throws Exception {
        Path file=temp.resolve("source.hwpx");
        String body="기관은 원본 자료를 검토하고 처리 결과를 기록하여야 한다. 변경 이력을 보존하고 원본과 대조하여 확인하여야 한다. ".repeat(8).trim();
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml"));
            zip.write(("<section><tbl colCnt='1'><tr><tc><cellAddr rowAddr='0' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/><t>"+body+"</t></tc></tr></tbl></section>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(temp.resolve("source.meta.json"),"{\"title\":\"자료 가이드\"}");
        var mapper=mock(RagDocumentMapper.class);
        when(mapper.findDocumentIdByHash(anyString())).thenReturn(42L);
        when(mapper.findChunkIdsByDocumentId(42L)).thenReturn(List.of());
        var service=new RagImportService(mapper,new RagTextExtractor(),new RagChunker(),new RagChunkQualityGate(),
            mock(RagChunkSearchIndexService.class),null,null,null,new tools.jackson.databind.ObjectMapper(),transactions());
        service.importFile(file,"official_doc",false,false);
        var rows=org.mockito.ArgumentCaptor.forClass(com.kaces.pandora.rag.document.RagDocumentChunkRow.class);
        verify(mapper,atLeastOnce()).insertChunk(rows.capture());
        var proof=new tools.jackson.databind.ObjectMapper().readTree(rows.getValue().sourceProvenance());
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        assertThat(proof.get("sourceFileHash").asText()).isEqualTo(hash);
        assertThat(rows.getValue().documentId()).isEqualTo(42);
    }

    @Test
    void acceptedNewDocumentRebindsDraftChunksToTheAllocatedDocumentId() throws Exception {
        Path file=temp.resolve("accepted.txt");
        Files.writeString(file,"자료 처리 가이드\n\n"+
            "기관은 원본 자료를 검토하고 처리 결과를 기록하여야 한다. 담당자는 변경 이력을 보존하고 필요한 경우 원본과 대조하여 확인하여야 한다. ".repeat(8));
        var mapper=mock(RagDocumentMapper.class);
        var index=mock(RagChunkSearchIndexService.class);
        when(mapper.findDocumentIdByHash(anyString())).thenReturn(42L);
        when(mapper.findChunkIdsByDocumentId(42L)).thenReturn(List.of());
        var service=new RagImportService(mapper,new RagTextExtractor(),new RagChunker(),new RagChunkQualityGate(),
            index,null,null,null,null,transactions());
        service.importFile(file,"official_doc",false,false);
        var rows=org.mockito.ArgumentCaptor.forClass(com.kaces.pandora.rag.document.RagDocumentChunkRow.class);
        verify(mapper,atLeastOnce()).insertChunk(rows.capture());
        assertThat(rows.getAllValues()).isNotEmpty().allSatisfy(row->{
            assertThat(row.documentId()).isEqualTo(42L);
            assertThat(row.chunkText()).isNotBlank();
            assertThat(row.contentHash()).hasSize(64);
        });
        verify(index).rebuildDocument(42L,4);
    }

    @Test
    void failedPreferredFileMustNotDeactivateItsAlternativeDocument() throws Exception {
        Files.writeString(temp.resolve("guide.pdf"),"invalid PDF");
        Files.writeString(temp.resolve("guide.hwpx"),"alternative source is not reimported");
        var mapper=mock(RagDocumentMapper.class);
        var index=mock(RagChunkSearchIndexService.class);
        var qdrant=mock(com.kaces.pandora.infra.qdrant.QdrantClient.class);
        doAnswer(call->{((RagImportJobKey)call.getArgument(0)).setImportJobId(1L);return null;})
            .when(mapper).insertImportJob(any(),anyString(),any());
        when(mapper.findActiveChunkIdsByFilePathsAndVersion(anyList(),eq(4))).thenReturn(List.of(17L));
        var service=new RagImportService(mapper,new RagTextExtractor(),new RagChunker(),new RagChunkQualityGate(),
            index,null,qdrant,null,null,transactions());
        var result=service.importFolder("official_doc",temp.toString(),false,true);
        assertThat(result.failedCount()).isEqualTo(1);
        verify(mapper,never()).deactivateChunksByFilePathsAndVersion(anyList(),anyInt());
        verify(mapper,never()).deactivateDocumentsByFilePaths(anyList());
        verify(mapper,never()).markEmbeddingsSupersededByChunkIds(anyList());
        verifyNoInteractions(qdrant,index);
    }

    @Test
    void alternativesAreCleanedOnlyAfterThePreferredDocumentIsIndexed() throws Exception {
        for(boolean indexNow : new boolean[]{false,true}) {
            Path folder=Files.createDirectory(temp.resolve("index-"+indexNow));
            Files.writeString(folder.resolve("guide.txt"),"자료 처리 가이드\n\n"+
                "기관은 원본 자료를 검토하고 처리 결과를 기록하여야 한다. 담당자는 변경 이력을 보존하고 필요한 경우 원본과 대조하여 확인하여야 한다. ".repeat(8));
            Path alternative=folder.resolve("guide.md");
            Files.writeString(alternative,"기존 대체 문서");
            var mapper=mock(RagDocumentMapper.class);
            var index=mock(RagChunkSearchIndexService.class);
            var qdrant=mock(com.kaces.pandora.infra.qdrant.QdrantClient.class);
            var embedding=mock(com.kaces.pandora.infra.openai.OpenAiEmbeddingClient.class);
            var properties=mock(com.kaces.pandora.semantic.config.LawAiProperties.class,RETURNS_DEEP_STUBS);
            when(properties.openai().embeddingModel()).thenReturn("fixture");
            when(properties.qdrant().ragCollection()).thenReturn("fixture");
            when(properties.rag().importBatchSize()).thenReturn(1);
            doAnswer(call->{((RagImportJobKey)call.getArgument(0)).setImportJobId(1L);return null;})
                .when(mapper).insertImportJob(any(),anyString(),any());
            when(mapper.findDocumentIdByHash(anyString())).thenReturn(42L);
            when(mapper.findChunkIdsByDocumentId(42L)).thenReturn(List.of());
            when(mapper.findActiveChunkIdsByFilePathsAndVersion(anyList(),eq(4))).thenReturn(List.of(17L));
            var row=new com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow(7L,42L,"official_doc","42","가이드","","","20260101","CURRENT",
                "chunk 1","가이드","자료 처리 본문",null,"$.v4.chunks[0]",null,0,"hash","가이드","body","PASS","자료 처리 본문",null,4);
            when(mapper.findSemanticIndexChunksByDocumentId(42L,4)).thenReturn(List.of(row));
            when(embedding.embed(anyList())).thenReturn(List.of(List.of(0.0)));
            var service=new RagImportService(mapper,new RagTextExtractor(),new RagChunker(),new RagChunkQualityGate(),
                index,embedding,qdrant,properties,null,transactions());
            var result=service.importFolder("official_doc",folder.toString(),indexNow,true);
            assertThat(result.failedCount()).isZero();
            assertThat(result.indexedCount()).isEqualTo(indexNow?1:0);
            if(indexNow) {
                var order=inOrder(mapper);
                order.verify(mapper).upsertEmbeddingStatus(eq(7L),anyString(),anyString(),anyString(),anyString(),eq("INDEXED"),isNull());
                order.verify(mapper).deactivateChunksByFilePathsAndVersion(eq(List.of(alternative.toAbsolutePath().toString())),eq(4));
                verify(qdrant).deleteRagPointsBestEffort(List.of(17L));
            } else {
                verify(mapper,never()).deactivateChunksByFilePathsAndVersion(anyList(),anyInt());
                verify(mapper,never()).deactivateDocumentsByFilePaths(anyList());
                verifyNoInteractions(qdrant,embedding);
            }
        }
    }

    @Test
    void rejectedReplacementCannotMutateDocumentOrActiveChunks() throws Exception {
        Path file=temp.resolve("rejected.txt");
        Files.writeString(file,"문서 제목");
        var mapper=mock(RagDocumentMapper.class);
        var index=mock(RagChunkSearchIndexService.class);
        when(mapper.findDocumentIdByHash(anyString())).thenReturn(42L);
        when(mapper.findChunkIdsByDocumentId(42L)).thenReturn(List.of(17L));
        var service=new RagImportService(mapper,new RagTextExtractor(),new RagChunker(),new RagChunkQualityGate(),
            index,null,null,null,null,transactions());
        assertThatThrownBy(()->service.importFile(file,"official_doc",false,true))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("quality gate rejected");
        verify(mapper).findDocumentByHash(anyString());
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(index);
    }

    private static org.springframework.transaction.PlatformTransactionManager transactions() {
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return manager;
    }
}
