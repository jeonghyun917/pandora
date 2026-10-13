package com.kaces.pandora.semantic.lexical;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	public boolean recoverAbandonedBuild(String indexVersion) {
		if (!rebuilding.compareAndSet(false, true)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Index maintenance is already running.");
		}
		try {
			return indexService.recoverAbandonedBuild(indexVersion);
		} finally {
			rebuilding.set(false);
		}
	}

	@PostMapping("/recover-abandoned")
	public boolean recover(
		@RequestParam String indexVersion,
		@RequestParam(defaultValue = "false") boolean confirmedAbandoned
	) {
		if (!confirmedAbandoned) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Abandoned build confirmation is required.");
		}
		try {
			return recoverAbandonedBuild(indexVersion);
		} catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid index version.");
		}
	}
}
