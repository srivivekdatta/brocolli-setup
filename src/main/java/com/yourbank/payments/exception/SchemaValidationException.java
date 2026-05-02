package com.yourbank.payments.exception;

import com.networknt.schema.ValidationMessage;
import java.util.Set;

public class SchemaValidationException extends RuntimeException {
    private final Set<ValidationMessage> errors;

    public SchemaValidationException(Set<ValidationMessage> errors) {
        super("Schema validation failed: " + errors);
        this.errors = errors;
    }

    public Set<ValidationMessage> getErrors() { return errors; }
}
