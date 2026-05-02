# Schema Change Runbook

This service is **schema-first**. `wire-payment-v1.json` is the source of truth.
Java POJOs under `src/generated/java/` are generated artifacts — never edit them by hand.

---

## Adding a new optional field (safe — backward compatible)

Example: adding `priority` to `transactionData`.

**Step 1 — update the schema**

```json
// wire-payment-v1.json → transactionData → properties
"priority": {
  "type": "string",
  "enum": ["HIGH", "NORMAL", "LOW"],
  "description": "Payment priority indicator"
}
// Do NOT add to "required" — optional field = backward compatible
```

**Step 2 — regenerate POJOs**

```bash
./gradlew generateJsonSchema2Pojo
```

**Step 3 — review the diff**

```bash
git diff src/generated/java/
```

The generator adds the field to `TransactionData.java` with `@JsonProperty("priority")`.
Verify: correct type, `@JsonInclude` present, no unexpected changes to other classes.

**Step 4 — check WirePaymentEntity**

`WirePaymentEntity` is hand-written (flat DB projection). If the new field needs to be
persisted, add it manually:

```java
// WirePaymentEntity.java — add the field
String priority,

// WirePaymentEntity.from() — map it
data.getPriority(),
```

**Step 5 — Oracle migration**

Create a new migration script:

```sql
-- db/migration/V2__add_priority_to_wire_payments.sql
ALTER TABLE wire_payments ADD priority VARCHAR2(10);
ALTER TABLE wire_payments ADD CONSTRAINT chk_wire_priority
    CHECK (priority IN ('HIGH', 'NORMAL', 'LOW'));
```

**Step 6 — commit everything together**

```
git add src/main/resources/schemas/wire-payment-v1.json
git add src/generated/java/
git add src/main/java/.../model/WirePaymentEntity.java
git add db/migration/V2__add_priority_to_wire_payments.sql
git commit -m "feat: add priority field to wire payment schema (v1 → optional field)"
```

---

## Adding a new required field (breaking — coordinate with producer)

A new `required` field is a **breaking change**. Old messages from the producer
will fail schema validation and route to the DLQ.

**Before touching any code:**
- Agree a cutover date with the producer team
- Producer deploys first (starts sending the new field)
- Confirm in Kafka that new field is present in messages
- Then deploy your consumer with the updated schema

**Step 1 — update schema, add to required array**

```json
"required": ["transactionId", "amount", "currency", "valueDate", "paymentType", "newField"]
```

**Step 2 — regenerate + review + persist + migrate** (same as optional field above)

**Step 3 — deploy order**
```
Producer deploys → validate messages in Kafka → Consumer deploys
```

Never deploy consumer first for a required field addition.

---

## Renaming a field (always breaking — avoid if possible)

Renaming is the most dangerous change. Old messages use the old name,
new messages use the new name — both will be in-flight during cutover.

**Preferred approach — dual-field transition:**

```json
// Phase 1: accept both names in schema
"oldFieldName": { "type": "string", "deprecated": true },
"newFieldName": { "type": "string" }

// Consumer code: prefer newFieldName, fall back to oldFieldName
String value = event.getNewFieldName() != null
    ? event.getNewFieldName()
    : event.getOldFieldName();
```

```
Phase 1: Deploy consumer accepting both fields
Phase 2: Producer migrates to newFieldName
Phase 3: Remove oldFieldName from schema, redeploy consumer
```

---

## Removing a field (breaking — coordinate with producer)

Same coordination process as adding a required field, but in reverse:

```
Consumer deploys (stops using the field) → Producer removes the field
```

Never remove from schema before the consumer stops referencing it.

---

## Regeneration command reference

```bash
# Regenerate POJOs from schema
./gradlew generateJsonSchema2Pojo

# Regenerate + compile in one shot (catches type errors immediately)
./gradlew generateJsonSchema2Pojo compileJava

# Full build including tests
./gradlew build

# See exactly what changed in generated code
git diff src/generated/java/
```

---

## Rules

| Rule | Reason |
|---|---|
| Never edit files under `src/generated/java/` | They are overwritten on next generation |
| Always commit schema + generated POJOs together | Keeps schema and code in sync in Git history |
| Optional fields only for producer-independent changes | Required fields need producer coordination |
| One migration script per schema version bump | Keeps DB history traceable |
| Review generated diff before committing | Catch unexpected type changes early |
