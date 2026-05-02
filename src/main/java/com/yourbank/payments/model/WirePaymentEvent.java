package com.yourbank.payments.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// ── Top-level envelope ────────────────────────────────────────────────────────
public record WirePaymentEvent(
    @JsonProperty("transaction") TransactionWrapper transaction,
    @JsonProperty("party")       PartyInfo party
) {}

// ── Transaction wrapper ───────────────────────────────────────────────────────
record TransactionWrapper(
    @JsonProperty("debitAccount")    AccountInfo debitAccount,
    @JsonProperty("creditAccount")   AccountInfo creditAccount,
    @JsonProperty("transactionData") TransactionData transactionData
) {}

// ── Account ───────────────────────────────────────────────────────────────────
record AccountInfo(
    @JsonProperty("accountNumber")     String accountNumber,
    @JsonProperty("routingNumber")     String routingNumber,
    @JsonProperty("bankName")          String bankName,
    @JsonProperty("bic")               String bic,
    @JsonProperty("accountHolderName") String accountHolderName,
    @JsonProperty("address")           PostalAddress address
) {}

// ── ISO 20022 PostalAddress24 ─────────────────────────────────────────────────
record PostalAddress(
    @JsonProperty("addressType")        String addressType,
    @JsonProperty("streetName")         String streetName,
    @JsonProperty("buildingNumber")     String buildingNumber,
    @JsonProperty("buildingName")       String buildingName,
    @JsonProperty("floor")              String floor,
    @JsonProperty("postBox")            String postBox,
    @JsonProperty("room")               String room,
    @JsonProperty("postCode")           String postCode,
    @JsonProperty("townName")           String townName,
    @JsonProperty("townLocationName")   String townLocationName,
    @JsonProperty("districtName")       String districtName,
    @JsonProperty("countrySubDivision") String countrySubDivision,
    @JsonProperty("country")            String country,
    @JsonProperty("addressLines")       List<String> addressLines
) {}

// ── Transaction data ──────────────────────────────────────────────────────────
record TransactionData(
    @JsonProperty("transactionId") UUID transactionId,
    @JsonProperty("amount")        BigDecimal amount,
    @JsonProperty("currency")      String currency,
    @JsonProperty("valueDate")     Instant valueDate,
    @JsonProperty("paymentType")   PaymentType paymentType,
    @JsonProperty("memo")          String memo,
    @JsonProperty("purposeCode")   String purposeCode
) {}

// ── Party ─────────────────────────────────────────────────────────────────────
record PartyInfo(
    @JsonProperty("userId")    String userId,
    @JsonProperty("companyId") String companyId
) {}

// ── Enums ─────────────────────────────────────────────────────────────────────
enum PaymentType { WIRE, ACH, FEDNOW }
