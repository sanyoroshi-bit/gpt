package jp.tenreader.launcher;

/** Process-local ownership. Consent and captured content are never persisted. */
final class CaptureTicket {
    private static long next, active;
    private static ScrollPort.Target target;
    static synchronized long begin() { return begin(null); }
    static synchronized long begin(ScrollPort.Target chosen) { active = ++next;target=chosen;return active; }
    static synchronized ScrollPort.Target target(long ticket){return owns(ticket)?target:null;}
    static synchronized boolean owns(long ticket) { return ticket != 0 && ticket == active; }
    static synchronized boolean busy() { return active != 0; }
    static synchronized void end(long ticket) { if (owns(ticket)){active=0;target=null;} }
    static synchronized void cancelAll() { active = 0;target=null; }
}
