package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.ai.answer.LawAiSearchFailureMapper;
import com.kaces.pandora.app.PandoraApplication;
import com.kaces.pandora.app.auth.AdminUserMapper;
import com.kaces.pandora.lawdata.persistence.LawAssetMapper;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawDocumentMapper;
import com.kaces.pandora.lawdata.persistence.LawSyncHistoryMapper;
import com.kaces.pandora.rag.collecting.RagCollectionMapper;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.batch.persistence.LawSemanticBatchJobChunkMapper;
import com.kaces.pandora.semantic.batch.persistence.LawSemanticBatchJobMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;

class PandoraApplicationMapperScanTests {

	@Test
	void mapperScanRegistersOnlyMapperAnnotatedInterfaces() {
		MapperScan mapperScan = PandoraApplication.class.getAnnotation(MapperScan.class);

		assertThat(mapperScan).isNotNull();
		assertThat(mapperScan.annotationClass()).isEqualTo(Mapper.class);
		assertThat(LawActivationTransactionExecutor.class.isAnnotationPresent(Mapper.class)).isFalse();
		assertThat(List.of(
			LawAiSearchFailureMapper.class,
			AdminUserMapper.class,
			LawAssetMapper.class,
			LawChunkMapper.class,
			LawDetailMapper.class,
			LawDocumentMapper.class,
			LawSyncHistoryMapper.class,
			RagCollectionMapper.class,
			RagDocumentMapper.class,
			LawSemanticBatchJobChunkMapper.class,
			LawSemanticBatchJobMapper.class
		)).allSatisfy(mapperType -> assertThat(mapperType.isAnnotationPresent(Mapper.class)).isTrue());
	}
}
