package jp.tenreader.launcher;

/** Process-local ownership. Consent and captured content are never persisted. */
final class CaptureTicket {
    private static long next, active;
    static synchronized long begin() { active = ++next; return active; }
    static synchronized boolean owns(long ticket) { return ticket != 0 && ticket == active; }
    static synchronized boolean busy() { return active != 0; }
    static synchronized void end(long ticket) { if (owns(ticket)) active = 0; }
    static synchronized void cancelAll() { active = 0; }
}
