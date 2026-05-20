package com.kaces.pandora.law.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import com.kaces.pandora.law.mapper.LawAssetMapper;
import com.kaces.pandora.law.mapper.LawChunkMapper;
import com.kaces.pandora.law.mapper.LawDetailMapper;
import com.kaces.pandora.law.mapper.LawDocumentMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LawDocumentWriter {

	private static final int MAX_STORED_DETAIL_JSON_CHARS = 4_000_000;

	private final LawDocumentMapper lawDocumentMapper;
	private final LawDetailMapper lawDetailMapper;
	private final LawChunkMapper lawChunkMapper;
	private final LawAssetMapper lawAssetMapper;
	private final ObjectMapper objectMapper;

	/**
	 * 臾몄꽌, ?곸꽭, 泥?겕, ?먯궛 Mapper? ???JSON 吏곷젹?붽린瑜?二쇱엯諛쏆뒿?덈떎.
	 */
	public LawDocumentWriter(
		LawDocumentMapper lawDocumentMapper,
		LawDetailMapper lawDetailMapper,
		LawChunkMapper lawChunkMapper,
		LawAssetMapper lawAssetMapper,
		ObjectMapper objectMapper
	) {
		this.lawDocumentMapper = lawDocumentMapper;
		this.lawDetailMapper = lawDetailMapper;
		this.lawChunkMapper = lawChunkMapper;
		this.lawAssetMapper = lawAssetMapper;
		this.objectMapper = objectMapper;
	}

	/**
	 * 寃??紐⑸줉 臾몄꽌瑜???ν븯怨??대? document_id瑜?諛섑솚?⑸땲??
	 */
	public long upsertDocument(SearchDocument document) {
		// DB ?좏깮 而щ읆? 鍮?臾몄옄?대낫??null??寃???쒖떆 泥섎━??紐낇솗?섎?濡?癒쇱? ?뺢퇋?뷀빀?덈떎.
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
		// ?먮낯 JSON ?댁떆瑜??④퍡 ??ν빐 ?댄썑 蹂寃?媛먯? 湲곗??쇰줈 ?ъ슜?????덇쾶 ?⑸땲??
		lawDocumentMapper.upsertDocument(normalized, sha256(document.rawJson()));
		// upsert ???대? PK媛 ?꾩슂?섎?濡?target + external_id濡??ㅼ떆 議고쉶?⑸땲??
		return lawDocumentMapper.findDocumentId(document.target(), document.externalId());
	}

	/**
	 * ?곸꽭 ?먮Ц????ν븯怨??대? detail_id瑜?諛섑솚?⑸땲??
	 */
	public long upsertDetail(long documentId, SyncDetailDocument detail, String rawJson) {
		// ?곸꽭 parser媛 留뚮뱺 ?뱀뀡 紐⑸줉??蹂꾨룄 JSON?쇰줈 ??ν빐 fallback ?쒖떆? 蹂寃?異붿쟻???ъ슜?⑸땲??
		String sectionsJson = toJson(detail.sections());
		// ?먮낯/?뱀뀡 湲몄씠? ?댁떆瑜?硫뷀?濡??④꺼 ??⑸웾 compact ?щ?瑜?異붿쟻?⑸땲??
		String metaJson = toJson(Map.of(
			"sectionCount", detail.sections().size(),
			"assetCount", detail.assets().size(),
			"rawJsonCharLength", rawJson.length(),
			"rawJsonContentHash", sha256(rawJson),
			"sectionsJsonCharLength", sectionsJson.length(),
			"sectionsJsonContentHash", sha256(sectionsJson)
		));
		// ?곸꽭 ?먮Ц???덈Т ?щ㈃ compact 硫뷀?濡??泥댄븳 ???곸꽭 ?뚯씠釉붿뿉 upsert?⑸땲??
		lawDetailMapper.upsertDetail(new StoredDetail(
			documentId,
			emptyToNull(detail.title()),
			metaJson,
			compactLargeJson(sectionsJson, "sections_json"),
			compactLargeJson(rawJson, "raw_json"),
			sha256(rawJson)
		));
		// ?댄썑 泥?겕/?먯궛 ??μ뿉 ?ъ슜???곸꽭 PK瑜?議고쉶?⑸땲??
		return lawDetailMapper.findDetailId(documentId);
	}

	/**
	 * ?대떦 臾몄꽌??湲곗〈 泥?겕瑜?援먯껜?섍퀬 ?덈줈 ??ν븳 泥?겕 ?섎? 諛섑솚?⑸땲??
	 */
	public int replaceChunks(long documentId, long detailId, List<SyncDetailSection> sections, String sourceUrl) {
		// ?곸꽭瑜??ㅼ떆 ?섏쭛?섎㈃ ?댁쟾 泥?겕?????댁긽 ?좊ː?????놁쑝誘濡?癒쇱? ?꾩껜 ??젣?⑸땲??
		lawChunkMapper.deleteChunks(documentId);
		int count = 0;
		for (SyncDetailSection section : sections) {
			String body = section.body();
			if (!StringUtils.hasText(body)) {
				continue;
			}
			// 蹂몃Ц ?댁떆? ?뺣젹 ?쒖꽌瑜??④퍡 ??ν빐 ?됱씤 ???蹂寃쎌쓣 異붿쟻?⑸땲??
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

	/**
	 * ?대떦 臾몄꽌??湲곗〈 ?먯궛??援먯껜?섍퀬 ?덈줈 ??ν븳 ?먯궛 ?섎? 諛섑솚?⑸땲??
	 */
	public int replaceAssets(long documentId, long detailId, List<SyncAsset> assets) {
		// ?먯궛???곸꽭 ?ъ닔吏?寃곌낵? 留욎텛湲??꾪빐 湲곗〈 媛믪쓣 癒쇱? ?쒓굅?⑸땲??
		lawAssetMapper.deleteAssets(documentId);
		int count = 0;
		for (SyncAsset asset : assets) {
			// ?먮낯 URL, ?꾨줉??URL, ?뚯씪紐낆쓣 ??ν빐 ?붾㈃ ?쒖떆? ?ㅼ슫濡쒕뱶 ?꾨줉?쒖뿉 ?쒖슜?⑸땲??
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

	/**
	 * ?덈Т ???곸꽭 JSON? DB ?⑦궥 ?쒕룄瑜??쇳븯?꾨줉 ?붿빟 硫뷀? JSON?쇰줈 ??ν빀?덈떎.
	 */
	private String compactLargeJson(String json, String fieldName) {
		if (json.length() <= MAX_STORED_DETAIL_JSON_CHARS) {
			return json;
		}
		// ?먮Ц ?꾩껜瑜???ν븯吏 紐삵븯??寃쎌슦?먮룄 湲몄씠? ?댁떆???④꺼 ?꾨씫 ?щ?瑜?異붿쟻?⑸땲??
		return toJson(Map.of(
			"storage", "compacted",
			"field", fieldName,
			"originalCharLength", json.length(),
			"originalContentHash", sha256(json),
			"reason", "exceeds-db-packet-safe-size"
		));
	}

	/**
	 * 鍮?臾몄옄?댁? DB null濡???λ릺?꾨줉 蹂?섑빀?덈떎.
	 */
	private String emptyToNull(String value) {
		return StringUtils.hasText(value) ? value : null;
	}

	/**
	 * 媛앹껜瑜?JSON 臾몄옄?대줈 吏곷젹?뷀빀?덈떎.
	 */
	private String toJson(Object value) {
		try {
			if (value instanceof JsonNode jsonNode) {
				// JsonNode??洹몃?濡?Jackson???섍꺼 ?먮낯 援ъ“瑜?蹂댁〈?⑸땲??
				return objectMapper.writeValueAsString(jsonNode);
			}
			// record DTO? ?쇰컲 媛앹껜瑜???μ슜 JSON 臾몄옄?대줈 蹂?섑빀?덈떎.
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("JSON serialization failed.", exception);
		}
	}

	/**
	 * 蹂寃?媛먯?? 以묐났 ?뺤씤???ъ슜??SHA-256 ?댁떆瑜??앹꽦?⑸땲??
	 */
	private String sha256(String value) {
		try {
			// Java ?쒖? MessageDigest濡?DB 蹂寃?媛먯?????SHA-256 媛믪쓣 怨꾩궛?⑸땲??
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(hash.length * 2);
			for (byte item : hash) {
				result.append(String.format("%02x", item));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}
}
