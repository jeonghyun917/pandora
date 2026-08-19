package com.kaces.pandora.semantic.lexical;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/semantic-lexical-index")
public class SemanticLexicalIndexAdminController {

	private final SemanticLexicalIndexService indexService;
	private final AtomicBoolean rebuilding = new AtomicBoolean();

	public SemanticLexicalIndexAdminController(SemanticLexicalIndexService indexService) {
		this.indexService = indexService;
	}

	@GetMapping("/status")
	public Status status() {
		return new Status(indexService.currentRevision(), rebuilding.get());
	}

	@PostMapping("/rebuild")
	public SemanticLexicalIndexService.BuildResult rebuild() {
		if (!rebuilding.compareAndSet(false, true)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Semantic lexical index rebuild is already running.");
		}
		try {
			return indexService.rebuild();
		} finally {
			rebuilding.set(false);
		}
	}

	public record Status(String readyRevision, boolean rebuilding) {
	}
}
