package com.bofa.authservice.service;

import com.bofa.authservice.model.AuthException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active sessions per user with idle and absolute timeouts and a
 * concurrent-session cap. Enforces FFIEC session-management expectations.
 */
@Service
public class SessionRegistry {

    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(15);
    static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(8);
    static final int MAX_CONCURRENT_SESSIONS = 3;

    private final Clock clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SessionRegistry() {
        this(Clock.systemUTC());
    }

    public SessionRegistry(Clock clock) {
        this.clock = clock;
    }

    public Session open(String username, String ipAddress, String userAgent) {
        if (username == null || username.isBlank()) {
            throw new AuthException("SESSION_INVALID", "Username is required");
        }
        List<Session> active = activeFor(username);
        if (active.size() >= MAX_CONCURRENT_SESSIONS) {
            Session oldest = active.stream().min(Comparator.comparing(Session::createdAt)).orElseThrow();
            sessions.remove(oldest.id());
        }
        Instant now = clock.instant();
        Session session = new Session(UUID.randomUUID().toString(), username, ipAddress, userAgent, now, now);
        sessions.put(session.id(), session);
        return session;
    }

    public Session touch(String sessionId, String ipAddress) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new AuthException("SESSION_INVALID", "Unknown session");
        }
        Instant now = clock.instant();
        if (Duration.between(session.lastSeenAt(), now).compareTo(IDLE_TIMEOUT) > 0) {
            sessions.remove(sessionId);
            throw new AuthException("SESSION_IDLE_TIMEOUT", "Session idle timeout");
        }
        if (Duration.between(session.createdAt(), now).compareTo(ABSOLUTE_TIMEOUT) > 0) {
            sessions.remove(sessionId);
            throw new AuthException("SESSION_ABSOLUTE_TIMEOUT", "Session exceeded maximum lifetime");
        }
        if (ipAddress != null && !ipAddress.equals(session.ipAddress())) {
            sessions.remove(sessionId);
            throw new AuthException("SESSION_IP_CHANGED", "Session IP address changed; re-authentication required");
        }
        Session updated = session.withLastSeen(now);
        sessions.put(sessionId, updated);
        return updated;
    }

    public void close(String sessionId) {
        sessions.remove(sessionId);
    }

    public int closeAll(String username) {
        int closed = 0;
        for (Session s : List.copyOf(sessions.values())) {
            if (s.username().equals(username)) {
                sessions.remove(s.id());
                closed++;
            }
        }
        return closed;
    }

    public List<Session> activeFor(String username) {
        Instant now = clock.instant();
        List<Session> result = new ArrayList<>();
        for (Session s : sessions.values()) {
            if (!s.username().equals(username)) {
                continue;
            }
            boolean idle = Duration.between(s.lastSeenAt(), now).compareTo(IDLE_TIMEOUT) > 0;
            boolean expired = Duration.between(s.createdAt(), now).compareTo(ABSOLUTE_TIMEOUT) > 0;
            if (idle || expired) {
                sessions.remove(s.id());
            } else {
                result.add(s);
            }
        }
        return result;
    }

    public record Session(String id, String username, String ipAddress, String userAgent, Instant createdAt,
                          Instant lastSeenAt) {
        Session withLastSeen(Instant at) {
            return new Session(id, username, ipAddress, userAgent, createdAt, at);
        }
    }
}
