package com.erolitechnologies.bizflow.licenceserver;

import com.erolitechnologies.bizflow.licensing.Edition;
import com.erolitechnologies.bizflow.licensing.LicenceData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plain form-encoded HTTP API (no JSON library, no object deserialisation).
 *   POST /v1/activate  key=...&device=...   -> signed token (text/plain)
 *   POST /v1/refresh   key=...&device=...   -> signed token
 *   POST /admin/issue  licensee, edition, kind, devices, days, grace   (X-Admin-Token)
 *   POST /admin/renew  id, days            POST /admin/revoke id, revoked=true|false     GET /admin/list
 * Production: run behind HTTPS (see LicenceServerMain); the server itself refuses to start without TLS unless told it is local dev.
 */
public final class LicenceHttpApi {
    private final LicenceManager manager;
    private final String adminToken;
    private final Map<String, int[]> rate = new ConcurrentHashMap<>(); // ip -> [count, windowStartMinute]

    public LicenceHttpApi(LicenceManager manager, String adminToken) {
        if (adminToken == null || adminToken.length() < 24) throw new IllegalArgumentException("Admin token must be at least 24 characters.");
        this.manager = manager; this.adminToken = adminToken;
    }

    public void register(HttpServer server) {
        server.createContext("/v1/activate", ex -> handle(ex, false, () -> manager.activate(form(ex).get("key"), form(ex).get("device"))));
        server.createContext("/v1/refresh", ex -> handle(ex, false, () -> manager.refresh(form(ex).get("key"), form(ex).get("device"))));
        server.createContext("/admin/issue", ex -> handle(ex, true, () -> {
            Map<String, String> f = form(ex);
            var i = manager.issue(f.get("licensee"), Edition.valueOf(f.getOrDefault("edition", "STARTER").toUpperCase()), LicenceData.Kind.valueOf(f.getOrDefault("kind", "ANNUAL").toUpperCase()),
                    Integer.parseInt(f.getOrDefault("devices", "1")), Integer.parseInt(f.getOrDefault("days", "365")), Integer.parseInt(f.getOrDefault("grace", "14")));
            return "id=" + i.licenceId() + "\nkey=" + i.licenceKey() + "\n";
        }));
        server.createContext("/admin/renew", ex -> handle(ex, true, () -> "expires=" + manager.renew(form(ex).get("id"), Integer.parseInt(form(ex).getOrDefault("days", "365"))) + "\n"));
        server.createContext("/admin/revoke", ex -> handle(ex, true, () -> { manager.setRevoked(form(ex).get("id"), Boolean.parseBoolean(form(ex).getOrDefault("revoked", "true"))); return "ok\n"; }));
        server.createContext("/admin/list", ex -> handle(ex, true, () -> {
            StringBuilder sb = new StringBuilder();
            for (var r : manager.list()) sb.append(r.licenceId).append('\t').append(r.licensee).append('\t').append(r.edition).append('\t').append(r.kind).append('\t')
                    .append(r.devices.size()).append('/').append(r.maxDevices).append('\t').append(r.expiresAt == null ? "never" : r.expiresAt.toString()).append('\t').append(r.revoked ? "REVOKED" : "active").append('\n');
            return sb.toString();
        }));
    }

    private interface Action { String run() throws Exception; }

    private final Map<HttpExchange, Map<String, String>> parsed = new ConcurrentHashMap<>();

    private Map<String, String> form(HttpExchange ex) throws IOException {
        Map<String, String> cached = parsed.get(ex);
        if (cached != null) return cached;
        byte[] body = ex.getRequestBody().readNBytes(8192);
        Map<String, String> out = new HashMap<>();
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        parsed.put(ex, out);
        return out;
    }

    private void handle(HttpExchange ex, boolean admin, Action action) throws IOException {
        int status = 200; String body;
        try {
            if (!ex.getRequestMethod().equals(admin && ex.getRequestURI().getPath().equals("/admin/list") ? "GET" : "POST")) throw new LicenceManager.LicenceException(405, "Method not allowed.");
            if (admin) {
                String t = ex.getRequestHeaders().getFirst("X-Admin-Token");
                if (!MessageDigestUtil.constantTimeEquals(MessageDigestUtil.sha256(adminToken), MessageDigestUtil.sha256(t == null ? "" : t)))
                    throw new LicenceManager.LicenceException(401, "Unauthorised.");
            } else if (!allow(ex.getRemoteAddress().getAddress().getHostAddress())) {
                throw new LicenceManager.LicenceException(429, "Too many requests. Try again in a minute.");
            }
            body = action.run();
        } catch (LicenceManager.LicenceException e) {
            status = e.httpStatus; body = "error=" + e.getMessage() + "\n";
        } catch (IllegalArgumentException e) {
            status = 400; body = "error=Invalid request.\n";
        } catch (Exception e) {
            status = 500; body = "error=Server error.\n";
        } finally {
            parsed.remove(ex);
        }
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private boolean allow(String ip) {
        int minute = (int) (System.currentTimeMillis() / 60000);
        int[] w = rate.compute(ip, (k, v) -> v == null || v[1] != minute ? new int[]{1, minute} : new int[]{v[0] + 1, minute});
        return w[0] <= 20;
    }
}
