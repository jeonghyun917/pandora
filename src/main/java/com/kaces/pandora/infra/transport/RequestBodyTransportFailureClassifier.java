package com.kaces.pandora.infra.transport;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.ResourceAccessException;

public final class RequestBodyTransportFailureClassifier {

	private static final String REQUEST_BODY_WRITE_FAILURE = "Error writing request body to server";
	private static final String SPRING_REQUEST_BODY_WRITE_FAILURE = "Could not write JSON: " + REQUEST_BODY_WRITE_FAILURE;

	private RequestBodyTransportFailureClassifier() {
	}

	public static boolean isExactTransientFailure(RuntimeException exception) {
		if (exception instanceof ResourceAccessException resourceAccessException) {
			return resourceAccessException.getCause() instanceof IOException ioException
				&& isExactRequestBodyTransportIOException(ioException);
		}
		return isExactSpringRequestBodyTransportWrapper(exception)
			&& causeChainContainsExactRequestBodyTransportIOException(exception);
	}

	public static boolean isExactSpringRequestBodyTransportWrapper(RuntimeException exception) {
		return exception instanceof HttpMessageNotWritableException
			&& SPRING_REQUEST_BODY_WRITE_FAILURE.equalsIgnoreCase(exception.getMessage());
	}

	public static boolean isExactRequestBodyTransportIOException(IOException exception) {
		return REQUEST_BODY_WRITE_FAILURE.equalsIgnoreCase(exception.getMessage());
	}

	private static boolean causeChainContainsExactRequestBodyTransportIOException(Throwable exception) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = exception; cause != null && seen.add(cause); cause = cause.getCause()) {
			if (cause instanceof IOException ioException && isExactRequestBodyTransportIOException(ioException)) {
				return true;
			}
		}
		return false;
	}
}
