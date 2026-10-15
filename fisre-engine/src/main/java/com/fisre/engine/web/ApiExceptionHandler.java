package com.fisre.engine.web;

import com.fisre.engine.rules.NotFoundException;
import com.fisre.engine.rules.WorkflowException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One JSON error shape for the UI: {"error": "..."}. Internal details are logged, never returned. */
@RestControllerAdvice
@ConditionalOnWebApplication
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception e) {
        String msg = e instanceof IllegalArgumentException ? e.getMessage() : "The request body is not valid JSON";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", msg));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(WorkflowException.class)
    ResponseEntity<Map<String, String>> conflict(WorkflowException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, String>> duplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Someone else changed this rule at the same time; reload and try again"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> other(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse er) {      // 404 for an unknown path, 405 for a wrong method, ...
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("error", "Not available"));
        }
        log.error("Unhandled error in the rule API", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal error; see the server log"));
    }
}
