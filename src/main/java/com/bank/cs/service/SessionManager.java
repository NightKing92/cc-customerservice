package com.bank.cs.service;

import com.bank.cs.model.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    @Value("${app.session-timeout-minutes:30}")
    private int timeoutMinutes;

    public SessionContext getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        String finalId = sessionId;
        SessionContext ctx = sessions.computeIfAbsent(finalId, SessionContext::new);
        ctx.touch();
        return ctx;
    }

    public SessionContext get(String sessionId) {
        SessionContext ctx = sessions.get(sessionId);
        if (ctx != null) ctx.touch();
        return ctx;
    }

    /** 定时清理过期会话 */
    @Scheduled(fixedRate = 60_000)
    public void cleanExpired() {
        long cutoff = System.currentTimeMillis() - (long) timeoutMinutes * 60 * 1000;
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().getLastActiveTime() < cutoff) {
                log.info("Session expired: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}
