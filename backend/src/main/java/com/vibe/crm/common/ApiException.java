package com.vibe.crm.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** Ошибка, которую нужно показать пользователю как есть. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final Map<String, String> fields;

	public ApiException(HttpStatus status, String message) {
		this(status, message, Map.of());
	}

	public ApiException(HttpStatus status, String message, Map<String, String> fields) {
		super(message);
		this.status = status;
		this.fields = fields;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public Map<String, String> getFields() {
		return fields;
	}

	public static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND, message);
	}

	public static ApiException badRequest(String field, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of(field, message));
	}

	public static ApiException conflict(String message) {
		return new ApiException(HttpStatus.CONFLICT, message);
	}

}
