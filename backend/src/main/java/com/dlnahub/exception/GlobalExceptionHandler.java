package com.dlnahub.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Builds the error body. Uses LinkedHashMap rather than Map.of because an exception
     * message can legitimately be null (e.g. a bare NullPointerException) and Map.of
     * rejects null values — which would make the error handler itself throw.
     */
    private static Map<String, Object> body(String message, Integer upnpErrorCode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message != null ? message : "Unexpected error");
        if (upnpErrorCode != null) {
            map.put("upnpErrorCode", upnpErrorCode);
        }
        return map;
    }

    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(DeviceNotFoundException e) {
        log.warn("Not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(e.getMessage(), null));
    }

    @ExceptionHandler(DlnaException.class)
    public ResponseEntity<Map<String, Object>> handleDlnaException(DlnaException e) {
        HttpStatus status = e.getUpnpErrorCode() > 0 ? HttpStatus.BAD_GATEWAY
                                                      : HttpStatus.INTERNAL_SERVER_ERROR;
        log.warn("DLNA error ({}): {}", e.getUpnpErrorCode(), e.getMessage());
        return ResponseEntity.status(status).body(body(e.getMessage(), e.getUpnpErrorCode()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(body(e.getMessage(), null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.error("Illegal state: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(e.getMessage(), null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(e.getMessage(), null));
    }
}
