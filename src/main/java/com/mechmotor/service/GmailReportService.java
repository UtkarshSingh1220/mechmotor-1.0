package com.mechmotor.service;

import org.springframework.stereotype.Service;
import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * Google installed-desktop OAuth2 with PKCE. No passwords, secrets, refresh
 * tokens, or access tokens are written to disk. Reconnect each app session.
 * Configure an OAuth Desktop client id using MECHMOTOR_GOOGLE_CLIENT_ID.
 */
@Service
public class GmailReportService {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private volatile String accessToken;
    private volatile Instant expiry=Instant.EPOCH;
    private static final String SCOPE="https://www.googleapis.com/auth/gmail.send";
    public boolean connected() {
        return accessToken!=null && Instant.now().isBefore(expiry.minusSeconds(60));
    }
    public synchronized void connect() throws Exception {
        String client=System.getenv("MECHMOTOR_GOOGLE_CLIENT_ID");
        if(client==null || client.isBlank())
            throw new IllegalStateException("Set MECHMOTOR_GOOGLE_CLIENT_ID to a Google OAuth Desktop client ID.");
        byte[] verifierBytes=new byte[32], nonce=new byte[24];
        SecureRandom random=new SecureRandom();
        random.nextBytes(verifierBytes);random.nextBytes(nonce);
        String verifier=Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        String challenge=Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state=Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(180000);
            int port=server.getLocalPort();
            String redirect="http://127.0.0.1:"+port+"/oauth/callback";
            String auth="https://accounts.google.com/o/oauth2/v2/auth?response_type=code"
                    +"&client_id="+enc(client)+"&redirect_uri="+enc(redirect)
                    +"&scope="+enc(SCOPE)+"&code_challenge="+enc(challenge)
                    +"&code_challenge_method=S256&state="+enc(state);
            if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                throw new IllegalStateException("Desktop browser is unavailable.");
            Desktop.getDesktop().browse(URI.create(auth));
            String code=null;
            while(code==null) {
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(10000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String line=reader.readLine();
                    if(line==null || !line.startsWith("GET ")) continue;
                    String location=line.split(" ")[1];
                    if(!location.startsWith("/oauth/callback?")) {
                        reply(socket,"Invalid callback URL.");continue;
                    }
                    Map<String,String> params=new HashMap<>();
                    for(String pair:location.substring(location.indexOf('?')+1).split("&")) {
                        String[] parts=pair.split("=",2);
                        params.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),
                                URLDecoder.decode(parts.length>1?parts[1]:"",StandardCharsets.UTF_8));
                    }
                    if(!state.equals(params.get("state"))) {
                        reply(socket,"Authorization state mismatch.");throw new SecurityException("OAuth state mismatch.");
                    }
                    if(params.containsKey("error")) {
                        reply(socket,"Google authorization was cancelled.");
                        throw new IllegalStateException("Google authorization: "+params.get("error"));
                    }
                    code=params.get("code");
                    reply(socket,code==null?"Missing authorization code.":"MechMotor is connected. You may close this window.");
                }
            }
            String form="client_id="+enc(client)+"&code="+enc(code)+"&code_verifier="+enc(verifier)
                    +"&redirect_uri="+enc(redirect)+"&grant_type=authorization_code";
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> result=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(result.statusCode()!=200)
                throw new IOException("Google token exchange failed (HTTP "+result.statusCode()+").");
            Matcher token=Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(result.body());
            Matcher ttl=Pattern.compile("\"expires_in\"\\s*:\\s*(\\d+)").matcher(result.body());
            if(!token.find()) throw new IOException("Google did not return an access token.");
            String obtained=token.group(1);
            long seconds=ttl.find()?Long.parseLong(ttl.group(1)):3500;
            accessToken=obtained;
            expiry=Instant.now().plusSeconds(seconds);
        }
    }
    private static void reply(Socket socket,String message) throws IOException {
        byte[] body=("<html><body><h2>"+message+"</h2></body></html>").getBytes(StandardCharsets.UTF_8);
        OutputStream out=socket.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);out.flush();
    }
    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    public void sendReport(List<String> addresses,Path csv,String reportName) throws Exception {
        if(!connected()) throw new IllegalStateException("Connect your Google account first.");
        if(addresses==null || addresses.isEmpty()) throw new IllegalArgumentException("Choose a recipient.");
        List<String> unique=addresses.stream().map(String::trim).distinct().toList();
        if(unique.size()>2) throw new IllegalArgumentException("At most two recipients are supported.");
        unique.forEach(WorkspaceUserService::requireEmail);
        if(!Files.isRegularFile(csv) || Files.size(csv)>20_000_000)
            throw new IllegalArgumentException("Report attachment must be a CSV file under 20 MB.");
        String boundary="mm"+UUID.randomUUID().toString().replace("-","");
        String subject=reportName.replaceAll("[\\r\\n]"," ")+" - MechMotor Report";
        String mime="To: "+String.join(", ",unique)+"\r\n"
                +"Subject: "+subject+"\r\nMIME-Version: 1.0\r\n"
                +"Content-Type: multipart/mixed; boundary=\""+boundary+"\"\r\n\r\n"
                +"--"+boundary+"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
                +"Attached is the requested MechMotor report.\r\n"
                +"--"+boundary+"\r\nContent-Type: text/csv; name=\"report.csv\"\r\n"
                +"Content-Disposition: attachment; filename=\"report.csv\"\r\n"
                +"Content-Transfer-Encoding: base64\r\n\r\n"
                +Base64.getMimeEncoder(76,"\r\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(Files.readAllBytes(csv))
                +"\r\n--"+boundary+"--\r\n";
        String raw=Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mime.getBytes(StandardCharsets.UTF_8));
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization","Bearer "+accessToken)
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"raw\":\""+raw+"\"}"))
                .build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)
            throw new IOException("Gmail send failed (HTTP "+response.statusCode()
                    +"). Reconnect Google if the session expired.");
    }
}
