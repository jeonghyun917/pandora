package com.kaces.pandora.infra.openai;

import com.kaces.pandora.infra.transport.RequestBodyTransportFailureClassifier;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OpenAiRequestBodyTransportRetry {

	private static final Logger log = LoggerFactory.getLogger(OpenAiRequestBodyTransportRetry.class);
	private static final int MAX_RETRIES = 1;
	private static final int MAX_DIAGNOSTIC_CAUSE_CHAIN_CLASSES = 8;
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
					failureRecorder.accept(failureSummary(exception, true, retries, true));
					Thread.currentThread().interrupt();
					throw interrupted;
				}
			}
		}
	}

	FailureSummary failureSummary(RuntimeException exception, boolean classifierAccepted, int attempts) {
		return failureSummary(exception, classifierAccepted, attempts, false);
	}

	FailureSummary failureSummary(
		RuntimeException exception,
		boolean classifierAccepted,
		int attempts,
		boolean interrupted
	) {
		List<String> causeChainClasses = new java.util.ArrayList<>();
		boolean requestBodyTransportMarkerExact = false;
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable cause = exception;
		while (cause != null && seen.add(cause) && causeChainClasses.size() < MAX_DIAGNOSTIC_CAUSE_CHAIN_CLASSES) {
			causeChainClasses.add(cause.getClass().getName());
			if (cause instanceof IOException ioException && RequestBodyTransportFailureClassifier.isExactRequestBodyTransportIOException(ioException)) {
				requestBodyTransportMarkerExact = true;
			}
			if (causeChainClasses.size() == MAX_DIAGNOSTIC_CAUSE_CHAIN_CLASSES) {
				break;
			}
			cause = cause.getCause();
		}
		boolean springOuterMarkerExact = RequestBodyTransportFailureClassifier.isExactSpringRequestBodyTransportWrapper(exception);
		return new FailureSummary(
			exception.getClass().getName(),
			List.copyOf(causeChainClasses),
			springOuterMarkerExact,
			requestBodyTransportMarkerExact,
			classifierAccepted,
			attempts,
			classifierAccepted && attempts > MAX_RETRIES,
			interrupted
		);
	}

	private boolean isRequestBodyWriteFailure(RuntimeException exception) {
		return RequestBodyTransportFailureClassifier.isExactTransientFailure(exception);
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
		boolean retryExhausted,
		boolean interrupted
	) {
	}
}
