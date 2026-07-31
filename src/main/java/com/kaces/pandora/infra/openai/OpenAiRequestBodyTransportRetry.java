package com.kaces.pandora.infra.openai;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

final class OpenAiRequestBodyTransportRetry {

	private static final Logger log = LoggerFactory.getLogger(OpenAiRequestBodyTransportRetry.class);
	private static final int MAX_RETRIES = 1;
	private static final Duration RETRY_DELAY = Duration.ofMillis(200);

	private final RetryDelay retryDelay;
	private final Consumer<RetryMetadata> retryRecorder;

	OpenAiRequestBodyTransportRetry() {
		this(delay -> Thread.sleep(delay.toMillis()), OpenAiRequestBodyTransportRetry::logRetry);
	}

	OpenAiRequestBodyTransportRetry(RetryDelay retryDelay, Consumer<RetryMetadata> retryRecorder) {
		this.retryDelay = retryDelay;
		this.retryRecorder = retryRecorder;
	}

	<T> T execute(String operation, RetryOperation<T> request) throws InterruptedException {
		int retries = 0;
		while (true) {
			try {
				return request.execute();
			} catch (RuntimeException exception) {
				if (retries >= MAX_RETRIES || !isRequestBodyWriteFailure(exception)) {
					throw exception;
				}
				retries++;
				retryRecorder.accept(new RetryMetadata(
					operation,
					retries,
					MAX_RETRIES,
					exception.getClass().getName(),
					rootCause(exception).getClass().getName()
				));
				try {
					retryDelay.pause(RETRY_DELAY);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw interrupted;
				}
			}
		}
	}

	private boolean isRequestBodyWriteFailure(RuntimeException exception) {
		return exception instanceof ResourceAccessException resourceAccessException
			&& resourceAccessException.getCause() instanceof IOException
			&& describesRequestBodyWrite(resourceAccessException.getMessage());
	}

	private boolean describesRequestBodyWrite(String message) {
		if (message == null) {
			return false;
		}
		String normalized = message.toLowerCase(java.util.Locale.ROOT);
		return normalized.contains("error writing request body");
	}

	private Throwable rootCause(Throwable exception) {
		Throwable root = exception;
		for (Throwable current : causeChain(exception)) {
			root = current;
		}
		return root;
	}

	private Iterable<Throwable> causeChain(Throwable exception) {
		java.util.List<Throwable> causes = new java.util.ArrayList<>();
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable current = exception; current != null && seen.add(current); current = current.getCause()) {
			causes.add(current);
		}
		return causes;
	}

	private static void logRetry(RetryMetadata metadata) {
		log.warn(
			"Retrying transient OpenAI request-body transport failure. operation={} retryNumber={} maxRetries={} outerExceptionClass={} rootCauseClass={}",
			metadata.operation(),
			metadata.retryNumber(),
			metadata.maxRetries(),
			metadata.outerExceptionClass(),
			metadata.rootCauseClass()
		);
	}

	@FunctionalInterface
	interface RetryOperation<T> {
		T execute();
	}

	@FunctionalInterface
	interface RetryDelay {
		void pause(Duration delay) throws InterruptedException;
	}

	record RetryMetadata(
		String operation,
		int retryNumber,
		int maxRetries,
		String outerExceptionClass,
		String rootCauseClass
	) {
	}
}
