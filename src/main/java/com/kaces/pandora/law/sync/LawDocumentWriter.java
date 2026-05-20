package com.kaces.pandora.law.sync;

import static com.kaces.pandora.law.common.LawHashUtils.sha256;
import static com.kaces.pandora.law.common.LawTextUtils.emptyToNull;

import com.kaces.pandora.law.common.LawJsonWriter;
import com.kaces.pandora.law.mapper.LawAssetMapper;
import com.kaces.pandora.law.mapper.LawChunkMapper;
import com.kaces.pandora.law.mapper.LawDetailMapper;
import com.kaces.pandora.law.mapper.LawDocumentMapper;
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
		lawDocumentMapper.upsertDocument(normalized, sha256(document.rawJson()));
		return lawDocumentMapper.findDocumentId(document.target(), document.externalId());
	}

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

		lawDetailMapper.upsertDetail(new StoredDetail(
			documentId,
			emptyToNull(detail.title()),
			metaJson,
			compactLargeJson(sectionsJson, "sections_json"),
			compactLargeJson(rawJson, "raw_json"),
			sha256(rawJson)
		));
		return lawDetailMapper.findDetailId(documentId);
	}

	public int replaceChunks(long documentId, long detailId, List<SyncDetailSection> sections, String sourceUrl) {
		lawChunkMapper.deleteChunks(documentId);
		int count = 0;
		for (SyncDetailSection section : sections) {
			String body = section.body();
			if (!StringUtils.hasText(body)) {
				continue;
			}
			lawChunkMapper.insertChunk(new StoredChunk(
				documentId,
				detailId,
				section.type(),
				emptyToNull(section.no()),
				emptyToNull(section.title()),
				body,
				null,
				emptyToNull(sourceUrl),
				count,
				sha256(body)
			));
			count++;
		}
		return count;
	}

	public int replaceAssets(long documentId, long detailId, List<SyncAsset> assets) {
		lawAssetMapper.deleteAssets(documentId);
		int count = 0;
		for (SyncAsset asset : assets) {
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

	private String toJson(Object value) {
		return jsonWriter.write(value);
	}
}
