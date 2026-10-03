package com.fisre.engine.rules;

/** A request that is valid but not allowed in the version's current state (maps to HTTP 409). */
public class WorkflowException extends RuntimeException {
    public WorkflowException(String message) {
        super(message);
    }
}
