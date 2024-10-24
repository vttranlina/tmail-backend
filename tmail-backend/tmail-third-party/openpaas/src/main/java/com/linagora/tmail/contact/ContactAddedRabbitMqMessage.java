package com.linagora.tmail.contact;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

public record ContactAddedRabbitMqMessage(String bookId, String bookName, String contactId,
                                          String userId, JCardObject vcard) {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static ContactAddedRabbitMqMessage fromJSON(byte[] jsonBytes) {
        try {
            return objectMapper.readValue(jsonBytes, ContactAddedRabbitMqMessage.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse ContactAddedRabbitMqMessage", e);
        }
    }
}