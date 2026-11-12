package com.safepay.notification.webhook;

public interface WebhookClient {
    void sendNotification(String transferId, String payload);
}
