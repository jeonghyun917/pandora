package com.kaces.pandora.infra.openai;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.ResourceAccessException;

final class OpenAiRequestBodyTransportRetry {

	private static final Logger log = LoggerFactory.getLogger(OpenAiRequestBodyTransportRetry.class);
	private static final int MAX_RETRIES = 1;
	private static final Duration RETRY_DELAY = Duration.ofMillis(200);
	private static final String REQUEST_BODY_WRITE_FAILURE = "Error writing request body to server";
	private static final String SPRING_REQUEST_BODY_WRITE_FAILURE = "Could not write JSON: " + REQUEST_BODY_WRITE_FAILURE;

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
		return execute(operation, request, ignored -> { });
	}

	<T> T execute(
		String operation,
		RetryOperation<T> request,
		Consumer<FailureSummary> failureRecorder
	) throws InterruptedException {
		int retries = 0;
		while (true) {
			try {
				return request.execute();
			} catch (RuntimeException exception) {
				boolean classifierAccepted = isRequestBodyWriteFailure(exception);
				if (retries >= MAX_RETRIES || !classifierAccepted) {
					failureRecorder.accept(failureSummary(exception, classifierAccepted, retries + 1));
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

	FailureSummary failureSummary(RuntimeException exception, boolean classifierAccepted, int attempts) {
		List<String> causeChainClasses = new java.util.ArrayList<>();
		boolean requestBodyTransportMarkerExact = false;
		for (Throwable cause : causeChain(exception)) {
			if (causeChainClasses.size() < 8) {
				causeChainClasses.add(cause.getClass().getName());
			}
			if (cause instanceof IOException ioException && isRequestBodyWriteTransportException(ioException)) {
				requestBodyTransportMarkerExact = true;
			}
		}
		boolean springOuterMarkerExact = exception instanceof HttpMessageNotWritableException
			&& describesSpringRequestBodyWrite(exception.getMessage());
		return new FailureSummary(
			exception.getClass().getName(),
			List.copyOf(causeChainClasses),
			springOuterMarkerExact,
			requestBodyTransportMarkerExact,
			classifierAccepted,
			attempts,
			classifierAccepted && attempts > MAX_RETRIES
		);
	}

	private boolean isRequestBodyWriteFailure(RuntimeException exception) {
		if (exception instanceof ResourceAccessException resourceAccessException) {
			return resourceAccessException.getCause() instanceof IOException ioException
				&& isRequestBodyWriteTransportException(ioException);
		}
		return exception instanceof HttpMessageNotWritableException messageNotWritableException
			&& describesSpringRequestBodyWrite(messageNotWritableException.getMessage())
			&& causeChainContainsRequestBodyWriteTransportException(messageNotWritableException);
	}

	private boolean causeChainContainsRequestBodyWriteTransportException(Throwable exception) {
		for (Throwable cause : causeChain(exception)) {
			if (cause instanceof IOException ioException && isRequestBodyWriteTransportException(ioException)) {
				return true;
			}
		}
		return false;
	}

	private boolean isRequestBodyWriteTransportException(IOException exception) {
		return REQUEST_BODY_WRITE_FAILURE.equalsIgnoreCase(exception.getMessage());
	}

	private boolean describesSpringRequestBodyWrite(String message) {
		return SPRING_REQUEST_BODY_WRITE_FAILURE.equalsIgnoreCase(message);
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

	record FailureSummary(
		String outerExceptionClass,
		List<String> causeChainClasses,
		boolean springOuterMarkerExact,
		boolean requestBodyTransportMarkerExact,
		boolean classifierAccepted,
		int attempts,
		boolean retryExhausted
	) {
	}
}
