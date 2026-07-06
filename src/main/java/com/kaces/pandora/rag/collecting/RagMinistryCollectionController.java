package com.kaces.pandora.rag.collecting;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag-collection/ministry")
public class RagMinistryCollectionController {
	private final RagMinistryCollectionService collectionService;

	public RagMinistryCollectionController(RagMinistryCollectionService collectionService) {
		this.collectionService = collectionService;
	}

	@GetMapping("/status")
	public ResponseEntity<RagCollectionResponse> status() {
		return ResponseEntity.ok(collectionService.status());
	}

	@PostMapping("/run")
	public ResponseEntity<RagCollectionResponse> run(
		@RequestParam(defaultValue = "ALL") String agency,
		@RequestParam(defaultValue = "true") boolean fillQueue,
		@RequestParam(defaultValue = "20") int maxArticles,
		@RequestParam(defaultValue = "3") int maxAttachmentsPerArticle
	) {
		return ResponseEntity.ok(collectionService.collect(agency, fillQueue, maxArticles, maxAttachmentsPerArticle));
	}
}
