package com.kaces.pandora.law.sync;

import java.util.List;
import java.util.Map;
import com.kaces.pandora.law.client.LawOpenApiService;
import com.kaces.pandora.law.mapper.LawSyncHistoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Service
public class LawOpenApiSyncService {

	private final LawOpenApiService lawOpenApiService;
	private final LawOpenApiPayloadParser payloadParser;
	private final LawDocumentWriter documentWriter;
	private final LawSyncHistoryMapper syncHistoryMapper;
	private final ObjectMapper objectMapper;

	/**
	 * ?몃? API ?몄텧, ?묐떟 ?뚯떛, DB ??? ?숆린???대젰 ???而댄룷?뚰듃瑜?二쇱엯諛쏆뒿?덈떎.
	 */
	public LawOpenApiSyncService(
		LawOpenApiService lawOpenApiService,
		LawOpenApiPayloadParser payloadParser,
		LawDocumentWriter documentWriter,
		LawSyncHistoryMapper syncHistoryMapper,
		ObjectMapper objectMapper
	) {
		this.lawOpenApiService = lawOpenApiService;
		this.payloadParser = payloadParser;
		this.documentWriter = documentWriter;
		this.syncHistoryMapper = syncHistoryMapper;
		this.objectMapper = objectMapper;
	}

	/**
	 * 援??踰뺣졊 API 紐⑸줉??議고쉶?섍퀬 臾몄꽌/?곸꽭/泥?겕/?먯궛 ????먮쫫???ㅽ뻾?⑸땲??
	 */
	@Transactional
	public SyncResult syncLaws(String target, String query, int page, int display, boolean fetchDetails) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		String safeQuery = StringUtils.hasText(query) ? query.trim() : "*";
		int safePage = Math.max(page, 1);
		int safeDisplay = Math.min(Math.max(display, 1), 100);
		// ?숆린???쒖옉怨??붿껌 ?뚮씪誘명꽣瑜??대젰 ?뚯씠釉붿뿉 癒쇱? ?④퉩?덈떎.
		long historyId = insertSyncHistory(safeTarget, safeQuery, safePage, safeDisplay, fetchDetails);

		try {
			// ?몃? 援??踰뺣졊 API?먯꽌 紐⑸줉 JSON??媛?몄샃?덈떎.
			String searchJson = lawOpenApiService.search(safeTarget, safeQuery, safePage, safeDisplay);
			// ?몃? API ?묐떟 援ъ“瑜?DB ??μ슜 SearchDocument 紐⑸줉?쇰줈 ?뺢퇋?뷀빀?덈떎.
			List<SearchDocument> documents = payloadParser.parseSearchDocuments(safeTarget, searchJson);
			// ?뺢퇋?붾맂 臾몄꽌瑜???ν븯怨??곸꽭/泥?겕/?먯궛 ???嫄댁닔瑜??꾩쟻?⑸땲??
			SyncCounters counters = syncDocuments(documents, fetchDetails);
			SyncResult result = new SyncResult(historyId, safeTarget, safeQuery, documents.size(), counters.details(), counters.chunks(), counters.assets());
			// ?깃났 寃곌낵瑜?JSON?쇰줈 ?④꺼 ?섏쨷???대뼡 ?묒뾽???섑뻾?먮뒗吏 異붿쟻?????덇쾶 ?⑸땲??
			markSyncSuccess(historyId, toJson(result));
			return result;
		} catch (Exception exception) {
			// ?ㅽ뙣?섎뜑?쇰룄 ?쒖옉 ?대젰? FAILED濡?留덇컧??以묎컙 ?곹깭媛 ?⑥? ?딄쾶 ?⑸땲??
			markSyncFailure(historyId, exception);
			throw new IllegalStateException("Law API sync failed: " + exception.getMessage(), exception);
		}
	}

	/**
	 * 寃??臾몄꽌 紐⑸줉??DB????ν븯怨??꾩슂?섎㈃ ?곸꽭源뚯? ?댁뼱????ν빀?덈떎.
	 */
	private SyncCounters syncDocuments(List<SearchDocument> documents, boolean fetchDetails) {
		int detailCount = 0;
		int chunkCount = 0;
		int assetCount = 0;
		for (SearchDocument document : documents) {
			// 紐⑸줉 臾몄꽌??upsert濡???ν빐 湲곗〈 臾몄꽌??媛깆떊?섍퀬 ?좉퇋 臾몄꽌??異붽??⑸땲??
			long documentId = documentWriter.upsertDocument(document);
			if (!fetchDetails || !StringUtils.hasText(document.detailLink())) {
				continue;
			}

			// 紐⑸줉???곸꽭留곹겕濡??먮Ц ?곸꽭 JSON/HTML 蹂??寃곌낵瑜?媛?몄샃?덈떎.
			String detailJson = lawOpenApiService.detail(document.detailLink());
			// ?곸꽭 ?먮Ц???쒕ぉ, 蹂몃Ц ?뱀뀡, 泥⑤? ?먯궛?쇰줈 遺꾨━?⑸땲??
			SyncDetailDocument detail = payloadParser.parseDetailDocument(detailJson, document.title());
			// ?곸꽭 ?먮Ц怨??뚯떛???뱀뀡 JSON???곸꽭 ?뚯씠釉붿뿉 ??ν빀?덈떎.
			long detailId = documentWriter.upsertDetail(documentId, detail, detailJson);
			// 寃???됱씤??泥?겕??湲곗〈 媛믪쓣 吏?곌퀬 ?꾩옱 ?곸꽭 湲곗??쇰줈 ?ㅼ떆 ?앹꽦?⑸땲??
			chunkCount += documentWriter.replaceChunks(documentId, detailId, detail.sections(), document.detailLink());
			// 泥⑤? ?뚯씪怨??대?吏 ?먯궛???꾩옱 ?곸꽭 湲곗??쇰줈 援먯껜?⑸땲??
			assetCount += documentWriter.replaceAssets(documentId, detailId, detail.assets());
			detailCount++;
		}
		return new SyncCounters(detailCount, chunkCount, assetCount);
	}

	/**
	 * ?숆린???쒖옉 ?대젰??湲곕줉?섍퀬 ?대젰 ID瑜?諛섑솚?⑸땲??
	 */
	private long insertSyncHistory(String target, String query, int page, int display, boolean fetchDetails) {
		// ?붿껌 ?뚮씪誘명꽣 ?꾩껜瑜?JSON?쇰줈 ??ν빐 媛숈? 議곌굔???숆린?붾? ?ы쁽?????덇쾶 ?⑸땲??
		String requestJson = toJson(Map.of(
			"target", target,
			"query", query,
			"page", page,
			"display", display,
			"fetchDetails", fetchDetails
		));
		// RUNNING ?곹깭 ?대젰???앹꽦?⑸땲??
		syncHistoryMapper.insertSyncHistory(target, requestJson);
		// 媛숈? DB ?몄뀡?먯꽌 ?앹꽦???대젰 ID瑜?媛?몄샃?덈떎.
		return syncHistoryMapper.lastInsertId();
	}

	/**
	 * ?숆린???깃났 寃곌낵瑜??대젰????ν빀?덈떎.
	 */
	private void markSyncSuccess(long historyId, String responseJson) {
		// 泥섎━ 寃곌낵瑜?response_json????ν븯怨?finished_at??梨꾩썎?덈떎.
		syncHistoryMapper.markSyncSuccess(historyId, responseJson);
	}

	/**
	 * ?숆린???ㅽ뙣 ?ъ쑀瑜??대젰????ν빀?덈떎.
	 */
	private void markSyncFailure(long historyId, Exception exception) {
		// ?덉쇅 硫붿떆吏瑜??대젰???④꺼 ?ㅽ뙣 ?먯씤??DB?먯꽌 諛붾줈 ?뺤씤?????덇쾶 ?⑸땲??
		syncHistoryMapper.markSyncFailure(historyId, exception.getMessage());
	}

	/**
	 * ?대젰 ??μ슜 ?묐떟 媛앹껜瑜?JSON 臾몄옄?대줈 吏곷젹?뷀빀?덈떎.
	 */
	private String toJson(Object value) {
		try {
			// record 湲곕컲 寃곌낵 媛앹껜瑜??숆린???대젰 ??μ슜 JSON?쇰줈 蹂?섑빀?덈떎.
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("JSON serialization failed.", exception);
		}
	}

	/**
	 * ?곸꽭 ???怨쇱젙?먯꽌 ?꾩쟻??泥섎━ 嫄댁닔?낅땲??
	 */
	private record SyncCounters(int details, int chunks, int assets) {
	}

	public record SyncResult(
		long syncHistoryId,
		String target,
		String query,
		int documents,
		int details,
		int chunks,
		int assets
	) {
	}
}
