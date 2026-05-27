package com.kaces.pandora.lawdata.sync;


import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.common.text.LawTextUtils;
import static com.kaces.pandora.common.text.LawHashUtils.sha256;
import static com.kaces.pandora.common.text.LawTextUtils.emptyToNull;

import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.lawdata.persistence.LawAssetMapper;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawDocumentMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LawDocumentWriter {

	private static final int MAX_STORED_DETAIL_JSON_CHARS = 4_000_000;

	private final LawDocumentMapper lawDocumentMapper;
	private final LawDetailMapper lawDetailMapper;
	private final LawChunkMapper lawChunkMapper;
	private final LawAssetMapper lawAssetMapper;
	private final LawJsonWriter jsonWriter;

	
	public LawDocumentWriter(
		LawDocumentMapper lawDocumentMapper,
		LawDetailMapper lawDetailMapper,
		LawChunkMapper lawChunkMapper,
		LawAssetMapper lawAssetMapper,
		LawJsonWriter jsonWriter
	) {
		this.lawDocumentMapper = lawDocumentMapper;
		this.lawDetailMapper = lawDetailMapper;
		this.lawChunkMapper = lawChunkMapper;
		this.lawAssetMapper = lawAssetMapper;
		this.jsonWriter = jsonWriter;
	}

	
	// 메소드 설명: upsertDocument 처리 흐름을 수행합니다.
	public long upsertDocument(SearchDocument document) {
		
		SearchDocument normalized = new SearchDocument(
			document.target(),
			document.externalId(),
			document.title(),
			emptyToNull(document.agencyName()),
			emptyToNull(document.categoryName()),
			emptyToNull(document.sourceDate()),
			emptyToNull(document.detailLink()),
			document.rawJson()
		);
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		lawDocumentMapper.upsertDocument(normalized, sha256(document.rawJson()));
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return lawDocumentMapper.findDocumentId(document.target(), document.externalId());
	}

	
	// 메소드 설명: upsertDetail 처리 흐름을 수행합니다.
	public long upsertDetail(long documentId, SyncDetailDocument detail, String rawJson) {
		
		String sectionsJson = toJson(detail.sections());
		String metaJson = toJson(Map.of(
			"sectionCount", detail.sections().size(),
			"assetCount", detail.assets().size(),
			"rawJsonCharLength", rawJson.length(),
			"rawJsonContentHash", sha256(rawJson),
			"sectionsJsonCharLength", sectionsJson.length(),
			"sectionsJsonContentHash", sha256(sectionsJson)
		));

		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		lawDetailMapper.upsertDetail(new StoredDetail(
			documentId,
			emptyToNull(detail.title()),
			metaJson,
			compactLargeJson(sectionsJson, "sections_json"),
			compactLargeJson(rawJson, "raw_json"),
			sha256(rawJson)
		));
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return lawDetailMapper.findDetailId(documentId);
	}

	
	// 메소드 설명: replaceChunks 처리 흐름을 수행합니다.
	public int replaceChunks(long documentId, long detailId, List<SyncDetailSection> sections, String sourceUrl) {
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		lawChunkMapper.deleteChunks(documentId);
		int count = 0;
		for (SyncDetailSection section : sections) {
			String body = section.body();
			if (!StringUtils.hasText(body)) {
				continue;
			}
			
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			lawChunkMapper.insertChunk(new StoredChunk(
				documentId,
				detailId,
				section.type(),
				emptyToNull(section.no()),
				emptyToNull(section.title()),
				body,
				emptyToNull(section.sourcePath()),
				emptyToNull(sourceUrl),
				count,
				sha256(body)
			));
			count++;
		}
		return count;
	}

	
	// 메소드 설명: replaceAssets 처리 흐름을 수행합니다.
	public int replaceAssets(long documentId, long detailId, List<SyncAsset> assets) {
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		lawAssetMapper.deleteAssets(documentId);
		int count = 0;
		for (SyncAsset asset : assets) {
			
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			lawAssetMapper.insertAsset(new StoredAsset(
				documentId,
				detailId,
				asset.type(),
				asset.sourceUrl(),
				asset.proxyUrl(),
				emptyToNull(asset.fileName()),
				emptyToNull(asset.fileExtension()),
				null,
				emptyToNull(asset.altText()),
				toJson(asset),
				count
			));
			count++;
		}
		return count;
	}

	
	// 메소드 설명: compactLargeJson 처리 흐름을 수행합니다.
	private String compactLargeJson(String json, String fieldName) {
		if (json.length() <= MAX_STORED_DETAIL_JSON_CHARS) {
			return json;
		}
		return toJson(Map.of(
			"storage", "compacted",
			"field", fieldName,
			"originalCharLength", json.length(),
			"originalContentHash", sha256(json),
			"reason", "exceeds-db-packet-safe-size"
		));
	}

	
	// 메소드 설명: toJson 처리 흐름을 수행합니다.
	private String toJson(Object value) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return jsonWriter.write(value);
	}
}
