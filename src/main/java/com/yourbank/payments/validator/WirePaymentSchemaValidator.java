package com.yourbank.payments.validator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.yourbank.payments.exception.MalformedPayloadException;
import com.yourbank.payments.exception.SchemaValidationException;
import com.yourbank.payments.model.WirePaymentEvent;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Validates raw Kafka JSON against wire-payment-v1.json schema.
 *
 * Two-phase:
 *   1. Schema validation  → SchemaValidationException   → route to DLQ
 *   2. Deserialization    → MalformedPayloadException    → route to DLQ
 *
 * Schema-valid but unmappable should never happen in practice —
 * it would indicate a bug in the schema itself.
 */
@Component
public class WirePaymentSchemaValidator {

    private final ObjectMapper objectMapper;
    private final String schemaPath;
    private JsonSchema schema;

    public WirePaymentSchemaValidator(
            ObjectMapper objectMapper,
            @Value("${app.wire.schema-path}") String schemaPath) {
        this.objectMapper = objectMapper;
        this.schemaPath   = schemaPath;
    }

    @PostConstruct
    public void init() throws IOException {
        JsonSchemaFactory factory = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V7);
        try (InputStream is = getClass().getResourceAsStream(schemaPath)) {
            if (is == null) {
                throw new IllegalStateException(
                    "Wire payment schema not found at: " + schemaPath);
            }
            this.schema = factory.getSchema(is);
        }
    }

    /**
     * Validates and deserializes a raw JSON string.
     * @throws SchemaValidationException  if payload fails schema validation
     * @throws MalformedPayloadException  if payload is not valid JSON
     */
    public WirePaymentEvent validateAndDeserialize(String raw) {
        JsonNode node = parseJson(raw);
        validateSchema(node);
        return deserialize(node);
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new MalformedPayloadException("Unparseable JSON payload", e);
        }
    }

    private void validateSchema(JsonNode node) {
        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            throw new SchemaValidationException(errors);
        }
    }

    private WirePaymentEvent deserialize(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, WirePaymentEvent.class);
        } catch (JsonProcessingException e) {
            throw new MalformedPayloadException(
                "Schema-valid but failed to deserialize — check schema/POJO alignment", e);
        }
    }
}
