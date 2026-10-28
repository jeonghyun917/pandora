package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.ai.answer.RuntimeConfigurationIdentity;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.version.*;
import com.kaces.pandora.rag.recovery.*;
import com.kaces.pandora.semantic.config.*;
import com.kaces.pandora.semantic.lexical.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt-in workflow wiring only. Reader/writer fencing remains on independently of mutation availability. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="pandora.source-version.workflow-enabled",havingValue="true")
public class SourceVersionConfiguration {
    @Bean
    public SourceVersionPreparationService sourceVersionPreparation(JdbcTemplate db,SourceVersionCandidateStore store,
            LawDocumentWriter writer,LawChunkMapper chunks,LawOpenApiPayloadParser parser,PlatformTransactionManager manager,
            @Value("${pandora.rag.recovery.product-sha:}") String product) {
        return new SourceVersionPreparationService(db,store,writer,chunks,parser,manager,product);
    }
    @Bean
    public SourceVersionEmbeddingReceipts sourceVersionEmbeddingReceipts(JdbcTemplate db,SourceVersionPreparationService preparation,
            PlatformTransactionManager manager,LawAiProperties properties) {
        return new SourceVersionEmbeddingReceipts(db,preparation,manager,properties.openai().embeddingModel(),properties.qdrant().vectorSize());
    }
    @Bean
    public SourceVersionVectorPreparation sourceVersionVectorPreparation(JdbcTemplate db,SourceVersionPreparationService preparation,
            SourceVersionEmbeddingReceipts receipts,LawChunkMapper chunks,OpenAiEmbeddingClient openai,QdrantClient qdrant,
            PlatformTransactionManager manager,LawAiProperties properties) {
        return new SourceVersionVectorPreparation(db,preparation,receipts,chunks,openai,qdrant,manager,properties.openai().embeddingModel(),properties.qdrant().collection());
    }
    @Bean
    public SourceVersionTransitionService sourceVersionTransitions(JdbcTemplate db,SourceVersionCandidateStore store,
            SourceVersionPreparationService preparation,SourceVersionVectorPreparation vectors,LawChunkMapper chunks,
            SourceVersionCorpusService sources,RagRecoveryMapper recovery,RagRecoveryRoutingService routing,
            SemanticLexicalMapper lexical,SemanticLexicalIndexService index,PlatformTransactionManager manager,
            @Value("${pandora.rag.recovery.product-sha:}") String product,LawAiProperties properties,LawAiLexicalProperties lexicalProperties,
            LawAiRrfProperties rrf,LawAiCoverageAwareProperties coverage,LawAiDocumentExpansionProperties expansion,LawAiLexicalVariantProperties variants) {
        String config=RuntimeConfigurationIdentity.sha256(properties,lexicalProperties,rrf,coverage,expansion,variants);
        return new SourceVersionTransitionService(db,store,preparation,vectors,chunks,sources,recovery,routing,lexical,index,manager,product,config);
    }
}
