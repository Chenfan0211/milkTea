package com.wuling.common.outbox;

/** event_outbox.status 的稳定取值。 */
public final class OutboxStatus {

    public static final String NEW = "NEW";
    public static final String PUBLISHING = "PUBLISHING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    private OutboxStatus() {
    }
}