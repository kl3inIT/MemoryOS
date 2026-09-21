package io.memoryos.iam.audit;

/**
 * The OCSF class an action belongs to, as Onyx routes its events, so a SIEM can subscribe to "who signed in" or
 * "who changed authority" without knowing every action MemoryOS will add.
 */
public enum AuditEventClass {
    AUTHENTICATION(3002),
    ACCOUNT_CHANGE(3001),
    USER_ACCESS_MANAGEMENT(3005),
    GROUP_MANAGEMENT(3006),
    API_ACTIVITY(6003);

    private final int ocsfClassId;

    AuditEventClass(int ocsfClassId) { this.ocsfClassId = ocsfClassId; }

    public int ocsfClassId() { return ocsfClassId; }

    /** The logger an event is re-emitted on, so one prefix captures the whole stream. */
    public String loggerName() { return "memoryos.audit." + name().toLowerCase(java.util.Locale.ROOT); }
}
