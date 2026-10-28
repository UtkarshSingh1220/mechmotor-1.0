package com.mechmotor.service;

import com.sun.net.httpserver.HttpServer;
import org.springframework.stereotype.Service;
import org.springframework.boot.json.JsonParserFactory;
import java.awt.Desktop;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Interactive installed-app Gmail OAuth using authorization code + PKCE.
 * Access tokens are kept in process memory only; no refresh tokens or client
 * secrets are written to the local database or application log.
 */
@Service
public class GmailReportSender {
    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String SEND = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15)).build();
    private volatile String accessToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public boolean connected() {
        return accessToken != null && Instant.now().isBefore(expiresAt.minusSeconds(45));
    }

    public void disconnect() {
        accessToken = null;
        expiresAt = Instant.EPOCH;
    }

    public String connect() throws Exception {
        String clientId = System.getenv("MECHMOTOR_GOOGLE_CLIENT_ID");
        if (clientId == null || clientId.isBlank())
            throw new IllegalStateException("Set MECHMOTOR_GOOGLE_CLIENT_ID to the Google Cloud OAuth Desktop client ID.");
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        new SecureRandom().nextBytes(nonce);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
        CompletableFuture<String> callback = new CompletableFuture<>();
        server.createContext("/callback", exchange -> {
            Map<String,String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            String body;
            if (!state.equals(params.get("state"))) {
                body="Authorization state mismatch; please close this tab.";
                callback.completeExceptionally(new IllegalArgumentException("OAuth state did not match."));
            } else if (params.containsKey("error")) {
                body="Google authorization was declined. You may close this tab.";
                callback.completeExceptionally(new IllegalStateException("Google authorization declined."));
            } else if (!params.containsKey("code")) {
                body="Authorization response has no code.";
                callback.completeExceptionally(new IllegalArgumentException("Missing authorization code."));
            } else {
                body="MechMotor Gmail authorization complete. Return to the application.";
                callback.complete(params.get("code"));
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            String authUrl = AUTH + "?response_type=code&client_id=" + enc(clientId)
                    + "&redirect_uri=" + enc(redirect)
                    + "&scope=" + enc("https://www.googleapis.com/auth/gmail.send")
                    + "&state=" + enc(state) + "&code_challenge=" + enc(challenge)
                    + "&code_challenge_method=S256&access_type=online";
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                throw new IllegalStateException("System browser is unavailable for Google sign-in.");
            Desktop.getDesktop().browse(URI.create(authUrl));
            String code = callback.get(180,TimeUnit.SECONDS);
            String form = "client_id=" + enc(clientId) + "&code=" + enc(code)
                    + "&code_verifier=" + enc(verifier) + "&redirect_uri=" + enc(redirect)
                    + "&grant_type=authorization_code";
            HttpResponse<String> token = HTTP.send(HttpRequest.newBuilder(URI.create(TOKEN))
                    .header("Content-Type","application/x-www-form-urlencoded")
                    .timeout(java.time.Duration.ofSeconds(25))
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (token.statusCode()!=200) throw new IllegalStateException(
                    "Gmail authorization failed (HTTP " + token.statusCode() + ").");
            Map<String,Object> data = JsonParserFactory.getJsonParser().parseMap(token.body());
            String granted = String.valueOf(data.getOrDefault("access_token",""));
            long duration = Long.parseLong(String.valueOf(data.getOrDefault("expires_in",3600)));
            if (granted.isBlank() || "null".equals(granted))
                throw new IllegalStateException("Google did not provide an access token.");
            accessToken = granted;
            expiresAt = Instant.now().plusSeconds(duration);
            return "Gmail connected for sending reports (this session).";
        } finally {
            server.stop(0);
        }
    }

    public void send(Path csv, List<String> recipients, String subject) throws Exception {
        if (!connected()) throw new IllegalStateException("Connect Gmail in Settings before sending.");
        if (recipients == null || recipients.isEmpty()) throw new IllegalArgumentException("Recipient required.");
        if (!Files.isRegularFile(csv)) throw new IllegalArgumentException("Generate a report before sending.");
        String safeName = csv.getFileName().toString().replaceAll("[^A-Za-z0-9._-]","_");
        String boundary = "mechmotor-" + UUID.randomUUID();
        String addresses = String.join(", ",recipients.stream()
                .map(WorkspaceAdministrationService::validateEmail).toList());
        // MIME headers and attachment name are locally generated, never arbitrary user input.
        String mime = "To: " + addresses + "\r\n"
                + "Subject: " + subject.replaceAll("[\\r\\n]"," ") + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\n\r\n"
                + "--" + boundary + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
                + "Attached is the requested MechMotor report.\r\n"
                + "--" + boundary + "\r\nContent-Type: text/csv; name=\"" + safeName + "\"\r\n"
                + "Content-Disposition: attachment; filename=\"" + safeName + "\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder(76,new byte[]{'\r','\n'}).encodeToString(Files.readAllBytes(csv))
                + "\r\n--" + boundary + "--\r\n";
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime.getBytes(StandardCharsets.UTF_8));
        String body = "{\"raw\":\"" + raw + "\"}";
        HttpResponse<String> result = HTTP.send(HttpRequest.newBuilder(URI.create(SEND))
                .header("Authorization","Bearer " + accessToken)
                .header("Content-Type","application/json")
                .timeout(java.time.Duration.ofSeconds(45))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        if (result.statusCode()!=200) {
            if (result.statusCode()==401) disconnect();
            throw new IllegalStateException("Gmail did not accept the message (HTTP "
                    + result.statusCode() + "). No successful delivery was confirmed.");
        }
    }

    private static Map<String,String> parseQuery(String raw) {
        Map<String,String> map = new HashMap<>();
        if (raw == null) return map;
        for (String entry:raw.split("&")) {
            String[] parts=entry.split("=",2);
            map.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),
                    parts.length==2 ? URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");
        }
        return map;
    }
    private static String enc(String text) {
        return URLEncoder.encode(text,StandardCharsets.UTF_8);
    }
}
