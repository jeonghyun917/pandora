package com.kaces.pandora.rag.collecting;

import com.kaces.pandora.rag.document.RagDocumentMeta;
import com.kaces.pandora.rag.document.RagDocumentRow;
import com.kaces.pandora.rag.importing.RagImportResponse;
import com.kaces.pandora.rag.importing.RagImportService;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.batch.LawSemanticBatchJobResponse;
import com.kaces.pandora.semantic.batch.LawSemanticBatchJobService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.ObjectMapper;

@Service
public class RagMinistryCollectionService {
	private static final List<String> IMPORTABLE_EXTENSIONS = List.of(".pdf", ".hwpx", ".docx", ".txt", ".md");
	private static final List<String> KNOWN_DOWNLOAD_EXTENSIONS = List.of(".pdf", ".hwpx", ".docx", ".hwp", ".doc", ".txt", ".md");
	private static final Pattern A_TAG_PATTERN = Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>");
	private static final Pattern HREF_PATTERN = Pattern.compile("(?i)href\\s*=\\s*([\"'])(.*?)\\1");
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final RagCollectionMapper collectionMapper;
	private final RagDocumentMapper documentMapper;
	private final RagImportService importService;
	private final LawSemanticBatchJobService batchJobService;
	private final ObjectMapper objectMapper;
	private final CollectedFileStore collectedFileStore;
	private final HttpClient httpClient;

	public RagMinistryCollectionService(
		RagCollectionMapper collectionMapper,
		RagDocumentMapper documentMapper,
		RagImportService importService,
		LawSemanticBatchJobService batchJobService,
		ObjectMapper objectMapper,
		CollectedFileStore collectedFileStore
	) {
		this.collectionMapper = collectionMapper;
		this.documentMapper = documentMapper;
		this.importService = importService;
		this.batchJobService = batchJobService;
		this.objectMapper = objectMapper;
		this.collectedFileStore = collectedFileStore;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	}

	public RagCollectionResponse status() {
		seedDefaultSources();
		return new RagCollectionResponse(
			0,
			"READY",
			"ALL",
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			null,
			collectionMapper.findSources()
		);
	}

	public RagCollectionResponse collect(
		String agencyCode,
		boolean fillQueue,
		int maxArticles,
		int maxAttachmentsPerArticle,
		boolean refreshExisting
	) {
		seedDefaultSources();
		String requestedAgency = normalizeAgency(agencyCode);
		RagCollectionRunKey run = new RagCollectionRunKey();
		collectionMapper.insertRun(run, requestedAgency);

		int discoveredArticles = 0;
		int newArticles = 0;
		int attachmentsDiscovered = 0;
		int downloadedCount = 0;
		int importedCount = 0;
		int skippedCount = 0;
		int failedCount = 0;
		int submittedBatches = 0;
		String lastError = null;

		List<RagCollectionSourceRow> sources = collectionMapper.findEnabledSources(requestedAgency);
		for (RagCollectionSourceRow source : sources) {
			if (!"RSS".equalsIgnoreCase(source.sourceType())) {
				continue;
			}
			try {
				List<RssItem> items = readRss(source.sourceUrl(), maxArticles);
				discoveredArticles += items.size();
				for (RssItem item : items) {
					RagSourceArticleKey articleKey = new RagSourceArticleKey();
					String externalId = item.guid().isBlank() ? sha256Text(item.link()) : item.guid();
					collectionMapper.upsertArticle(
						articleKey,
						source.sourceId(),
						externalId,
						limit(item.title(), 1000),
						item.link(),
						item.publishedAt(),
						null
					);
					Long articleId = articleKey.getArticleId() > 0
						? articleKey.getArticleId()
						: collectionMapper.findArticleId(source.sourceId(), externalId);
					if (articleId == null) {
						failedCount++;
						lastError = "Article id was not resolved for " + item.link();
						continue;
					}
					try {
						ArticleProcessResult result = processArticle(
							source,
							articleId,
							item,
							maxAttachmentsPerArticle,
							refreshExisting
						);
						attachmentsDiscovered += result.attachmentsDiscovered();
						downloadedCount += result.downloaded();
						importedCount += result.imported();
						skippedCount += result.skipped();
						failedCount += result.failed();
						if (result.imported() > 0) {
							newArticles++;
							collectionMapper.updateArticleStatus(articleId, "IMPORTED", null);
						} else if (result.skipped() > 0) {
							collectionMapper.updateArticleStatus(articleId, "SKIPPED", null);
						} else {
							collectionMapper.updateArticleStatus(articleId, "DISCOVERED", null);
						}
					} catch (Exception exception) {
						failedCount++;
						lastError = exception.getMessage();
						collectionMapper.updateArticleStatus(articleId, "FAILED", lastError);
					}
				}
				collectionMapper.markSourceChecked(source.sourceId(), true, null);
			} catch (Exception exception) {
				failedCount++;
				lastError = exception.getMessage();
				collectionMapper.markSourceChecked(source.sourceId(), false, lastError);
			}
		}

		if (fillQueue) {
			try {
				List<LawSemanticBatchJobResponse> responses = batchJobService.fillQueue("official_doc", "", 50000, 2);
				submittedBatches = (int) responses.stream()
					.filter(response -> response.openaiBatchId() != null && !response.openaiBatchId().isBlank())
					.count();
			} catch (Exception exception) {
				failedCount++;
				lastError = exception.getMessage();
			}
		}

		String status = failedCount > 0 ? (importedCount > 0 ? "PARTIAL_SUCCESS" : "FAILED") : "SUCCESS";
		collectionMapper.finishRun(
			run.getRunId(),
			status,
			discoveredArticles,
			newArticles,
			attachmentsDiscovered,
			downloadedCount,
			importedCount,
			skippedCount,
			failedCount,
			submittedBatches,
			lastError
		);
		return new RagCollectionResponse(
			run.getRunId(),
			status,
			requestedAgency,
			discoveredArticles,
			newArticles,
			attachmentsDiscovered,
			downloadedCount,
			importedCount,
			skippedCount,
			failedCount,
			submittedBatches,
			lastError,
			collectionMapper.findSources()
		);
	}

	private ArticleProcessResult processArticle(
		RagCollectionSourceRow source,
		long articleId,
		RssItem item,
		int maxAttachmentsPerArticle,
		boolean refreshExisting
	) throws Exception {
		String html = httpGetText(item.link());
		String detailHash = sha256Text(html);
		List<AttachmentCandidate> candidates = choosePdfFirst(findAttachments(item.link(), html));
		if (maxAttachmentsPerArticle > 0 && candidates.size() > maxAttachmentsPerArticle) {
			candidates = candidates.subList(0, maxAttachmentsPerArticle);
		}
		int discovered = candidates.size();
		int downloaded = 0;
		int imported = 0;
		int skipped = 0;
		int failed = 0;
		Path articleDir = articleDirectory(source.agencyCode(), articleId);
		Files.createDirectories(articleDir);
		for (AttachmentCandidate candidate : candidates) {
			try {
				if (!IMPORTABLE_EXTENSIONS.contains(candidate.extension())) {
					skipped++;
					collectionMapper.upsertAttachment(
						articleId,
						candidate.url(),
						candidate.fileName(),
						candidate.extension(),
						null,
						null,
						null,
						null,
						"SKIPPED",
						"Unsupported extension"
					);
					continue;
				}
				RagCollectedAttachmentRow existingAttachment =
					collectionMapper.findAttachment(articleId, candidate.url());
				if (collectedFileStore.shouldReuse(existingAttachment, articleDir, refreshExisting)) {
					skipped++;
					continue;
				}
				byte[] bytes = httpGetBytes(candidate.url());
				CollectedFileStore.StoredFile storedFile =
					collectedFileStore.store(articleDir, candidate.fileName(), bytes);
				Path destination = storedFile.path();
				String fileHash = storedFile.sha256();
				downloaded++;
				if (storedFile.created() || !Files.exists(metaPath(destination))) {
					writeMeta(destination, source, item, detailHash);
				}
				RagImportResponse response = importService.importFolder("official_doc", articleDir.toString(), false, false);
				RagDocumentRow document = documentMapper.findDocumentByHash(fileHash);
				Long documentId = document == null ? null : document.documentId();
				if ("FAILED".equals(response.status())) {
					failed++;
					collectionMapper.upsertAttachment(
						articleId,
						candidate.url(),
						candidate.fileName(),
						candidate.extension(),
						Files.probeContentType(destination),
						fileHash,
						destination.toAbsolutePath().toString(),
						documentId,
						"FAILED",
						response.message()
					);
				} else if (response.importedCount() > 0 || documentId != null) {
					imported++;
					collectionMapper.upsertAttachment(
						articleId,
						candidate.url(),
						candidate.fileName(),
						candidate.extension(),
						Files.probeContentType(destination),
						fileHash,
						destination.toAbsolutePath().toString(),
						documentId,
						"IMPORTED",
						null
					);
				} else {
					skipped++;
					collectionMapper.upsertAttachment(
						articleId,
						candidate.url(),
						candidate.fileName(),
						candidate.extension(),
						Files.probeContentType(destination),
						fileHash,
						destination.toAbsolutePath().toString(),
						documentId,
						"SKIPPED",
						null
					);
				}
			} catch (Exception exception) {
				failed++;
				collectionMapper.upsertAttachment(
					articleId,
					candidate.url(),
					candidate.fileName(),
					candidate.extension(),
					null,
					null,
					null,
					null,
					"FAILED",
					exception.getMessage()
				);
			}
		}
		return new ArticleProcessResult(discovered, downloaded, imported, skipped, failed);
	}

	private void seedDefaultSources() {
		collectionMapper.insertDefaultSource(
			"korea_policy_expdoc",
			"RSS",
			"GOV",
			"Government policy briefing",
			"https://www.korea.kr/rss/expdoc.xml"
		);
		collectionMapper.insertDefaultSource(
			"korea_policy_mois",
			"RSS",
			"MOIS",
			"Ministry of the Interior and Safety",
			"https://www.korea.kr/rss/dept_mois.xml"
		);
		collectionMapper.insertDefaultSource(
			"korea_policy_mcst",
			"RSS",
			"MCST",
			"Ministry of Culture, Sports and Tourism",
			"https://www.korea.kr/rss/dept_mcst.xml"
		);
		collectionMapper.insertDefaultSource(
			"korea_policy_msit",
			"RSS",
			"MSIT",
			"Ministry of Science and ICT",
			"https://www.korea.kr/rss/dept_msit.xml"
		);
	}

	private List<RssItem> readRss(String sourceUrl, int maxArticles) throws Exception {
		String xml = httpGetText(sourceUrl);
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		Document document = factory.newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
		NodeList itemNodes = document.getElementsByTagName("item");
		List<RssItem> items = new ArrayList<>();
		for (int index = 0; index < itemNodes.getLength(); index++) {
			if (maxArticles > 0 && items.size() >= maxArticles) {
				break;
			}
			Element item = (Element) itemNodes.item(index);
			String title = firstText(item, "title");
			String link = firstText(item, "link");
			if (title.isBlank() || link.isBlank()) {
				continue;
			}
			items.add(new RssItem(
				title,
				link,
				firstText(item, "guid"),
				parseDate(firstText(item, "pubDate"))
			));
		}
		return items;
	}

	private List<AttachmentCandidate> findAttachments(String baseLink, String html) {
		URI base = URI.create(baseLink);
		Map<String, AttachmentCandidate> candidates = new LinkedHashMap<>();
		Matcher linkMatcher = A_TAG_PATTERN.matcher(html);
		while (linkMatcher.find()) {
			addAttachmentCandidate(candidates, base, linkMatcher.group(2), stripTags(linkMatcher.group(3)));
		}
		Matcher matcher = HREF_PATTERN.matcher(html);
		while (matcher.find()) {
			addAttachmentCandidate(candidates, base, matcher.group(2), "");
		}
		return new ArrayList<>(candidates.values());
	}

	private void addAttachmentCandidate(
		Map<String, AttachmentCandidate> candidates,
		URI base,
		String rawHref,
		String linkText
	) {
		String raw = decodeHtml(rawHref);
		if (!isUsableHref(raw)) {
			return;
		}
		String absolute;
		try {
			absolute = base.resolve(raw).toString();
		} catch (IllegalArgumentException exception) {
			return;
		}
		String fileName = fileNameFromUrl(absolute);
		String extension = extensionOf(fileName);
		if (!isKnownDownloadExtension(extension)) {
			String textName = fileNameFromText(linkText);
			String textExtension = extensionOf(textName);
			if (isKnownDownloadExtension(textExtension)) {
				fileName = textName;
				extension = textExtension;
			}
		}
		if (!isKnownDownloadExtension(extension)) {
			extension = extensionFromUrl(absolute);
			if (!extension.isBlank()) {
				fileName = fileNameFromText(linkText);
				if (fileName.isBlank() || !isKnownDownloadExtension(extensionOf(fileName))) {
					fileName = "attachment" + extension;
				}
			}
		}
		if (isKnownDownloadExtension(extension)) {
			candidates.putIfAbsent(absolute, new AttachmentCandidate(absolute, safeFileName(fileName), extension));
		}
	}

	private List<AttachmentCandidate> choosePdfFirst(List<AttachmentCandidate> candidates) {
		List<AttachmentCandidate> pdfs = candidates.stream()
			.filter(candidate -> ".pdf".equals(candidate.extension()))
			.sorted(Comparator.comparing(AttachmentCandidate::fileName))
			.toList();
		if (!pdfs.isEmpty()) {
			return pdfs;
		}
		return candidates.stream()
			.sorted(Comparator.comparing(AttachmentCandidate::fileName))
			.toList();
	}

	private boolean isUsableHref(String raw) {
		if (raw == null || raw.isBlank()) {
			return false;
		}
		String trimmed = raw.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		return !trimmed.startsWith("#")
			&& !trimmed.contains("${")
			&& !trimmed.contains("}")
			&& !lower.startsWith("javascript:")
			&& !lower.startsWith("mailto:")
			&& !lower.startsWith("tel:");
	}

	private void writeMeta(Path file, RagCollectionSourceRow source, RssItem item, String detailHash) throws IOException {
		Path meta = metaPath(file);
		RagDocumentMeta documentMeta = new RagDocumentMeta(
			"official_doc",
			null,
			source.agencyName(),
			"ministry_doc",
			"rss " + source.sourceKey(),
			formatDate(item.publishedAt()),
			detailHash == null || detailHash.isBlank() ? source.sourceKey() : detailHash.substring(0, Math.min(16, detailHash.length())),
			1,
			item.link()
		);
		objectMapper.writeValue(meta.toFile(), documentMeta);
	}

	private Path metaPath(Path file) {
		String fileName = file.getFileName().toString();
		String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
		return file.resolveSibling(baseName + ".meta.json");
	}

	private Path articleDirectory(String agencyCode, long articleId) {
		String year = String.valueOf(LocalDateTime.now(KST).getYear());
		return Path.of("data", "rag-upload", "ministry_docs", agencyCode.toLowerCase(Locale.ROOT), year, String.valueOf(articleId))
			.toAbsolutePath()
			.normalize();
	}

	private String httpGetText(String url) throws Exception {
		byte[] bytes = httpGetBytes(url);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private byte[] httpGetBytes(String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(45))
			.header("User-Agent", "PandoraRagCollector/1.0")
			.GET()
			.build();
		HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("HTTP " + response.statusCode() + " " + url);
		}
		return response.body();
	}

	private String firstText(Element parent, String tagName) {
		NodeList nodes = parent.getElementsByTagName(tagName);
		if (nodes.getLength() == 0 || nodes.item(0) == null) {
			return "";
		}
		return nodes.item(0).getTextContent().trim();
	}

	private LocalDateTime parseDate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
				.atZoneSameInstant(KST)
				.toLocalDateTime();
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private String formatDate(LocalDateTime value) {
		return value == null ? null : value.format(DateTimeFormatter.BASIC_ISO_DATE);
	}

	private String normalizeAgency(String agencyCode) {
		if (agencyCode == null || agencyCode.isBlank()) {
			return "ALL";
		}
		String value = agencyCode.trim().toUpperCase(Locale.ROOT);
		return List.of("ALL", "GOV", "MOIS", "MCST", "MSIT", "PIPC").contains(value) ? value : "ALL";
	}

	private String fileNameFromUrl(String url) {
		try {
			String path = URI.create(url).getPath();
			String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
			name = URLDecoder.decode(name, StandardCharsets.UTF_8);
			return name.isBlank() ? "attachment" : name;
		} catch (Exception ignored) {
			return "attachment";
		}
	}

	private String fileNameFromText(String value) {
		String normalized = decodeHtml(value)
			.replace('\u00a0', ' ')
			.replaceAll("\\s+", " ")
			.trim();
		if (normalized.isBlank()) {
			return "";
		}
		String lower = normalized.toLowerCase(Locale.ROOT);
		for (String extension : KNOWN_DOWNLOAD_EXTENSIONS) {
			int end = lower.indexOf(extension);
			if (end >= 0) {
				return safeFileName(normalized.substring(0, end + extension.length()).trim());
			}
		}
		return safeFileName(normalized);
	}

	private String extensionFromUrl(String url) {
		String lower = url.toLowerCase(Locale.ROOT);
		for (String extension : KNOWN_DOWNLOAD_EXTENSIONS) {
			if (lower.contains(extension)) {
				return extension;
			}
		}
		return "";
	}

	private boolean isKnownDownloadExtension(String extension) {
		return extension != null && KNOWN_DOWNLOAD_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
	}

	private String extensionOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
	}

	private String safeFileName(String value) {
		String sanitized = String.valueOf(value == null ? "" : value)
			.replaceAll("[\\\\/:*?\"<>|]", "_")
			.replaceAll("\\s+", " ")
			.trim();
		return sanitized.isBlank() ? "attachment" : limit(sanitized, 180);
	}

	private String decodeHtml(String value) {
		return String.valueOf(value == null ? "" : value)
			.replace("&amp;", "&")
			.replace("&quot;", "\"")
			.replace("&#39;", "'")
			.trim();
	}

	private String stripTags(String value) {
		return decodeHtml(String.valueOf(value == null ? "" : value).replaceAll("(?is)<[^>]+>", " "));
	}

	private String limit(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}

	private String sha256Text(String value) {
		return sha256Bytes(String.valueOf(value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
	}

	private String sha256Bytes(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		} catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	private record RssItem(String title, String link, String guid, LocalDateTime publishedAt) {
	}

	private record AttachmentCandidate(String url, String fileName, String extension) {
	}

	private record ArticleProcessResult(
		int attachmentsDiscovered,
		int downloaded,
		int imported,
		int skipped,
		int failed
	) {
	}
}
