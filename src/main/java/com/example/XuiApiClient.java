package com.example;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XuiApiClient {
    private static final Logger log = LoggerFactory.getLogger(XuiApiClient.class);
    private final String panelUrl;
    private final String username;
    private final String password;
    private final String apiToken;
    private final int subPort;
    private final String subPath;
    private String sessionCookie;
    
    public XuiApiClient(String panelUrl, String username, String password, String apiToken, int subPort, String subPath) {
        this.panelUrl = panelUrl.endsWith("/") ? panelUrl.substring(0, panelUrl.length() - 1) : panelUrl;
        this.username = username;
        this.password = password;
        this.apiToken = apiToken;
        this.subPort = subPort;
        this.subPath = subPath.startsWith("/") ? subPath : "/" + subPath;
    }
    
    public boolean login() {
        try {
            URL url = new URL(panelUrl + "/login");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            
            JSONObject json = new JSONObject();
            json.put("username", username);
            json.put("password", password);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            
            String cookie = conn.getHeaderField("Set-Cookie");
            if (cookie != null) {
                sessionCookie = cookie.split(";")[0];
                log.info("Successfully logged in to 3x-ui panel");
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Login failed: {}", e.getMessage());
            return false;
        }
    }
    
    public Map<String, Long> getAllClientsExpiry() {
        Map<String, Long> clients = new HashMap<>();
        JSONObject res = makeApiRequest("/panel/api/inbounds/list", "GET");
        if (res != null && res.optBoolean("success")) {
            JSONArray inbounds = res.optJSONArray("obj");
            if (inbounds != null) {
                for (int i = 0; i < inbounds.length(); i++) {
                    JSONObject inbound = inbounds.getJSONObject(i);
                    
                    // 1. Scan stats
                    JSONArray stats = inbound.optJSONArray("clientStats");
                    if (stats != null) {
                        for (int j = 0; j < stats.length(); j++) {
                            JSONObject s = stats.getJSONObject(j);
                            String email = s.optString("email");
                            if (!email.isEmpty()) {
                                clients.put(email, s.optLong("expiryTime", 0));
                            }
                        }
                    }
                    
                    // 2. Scan settings clients
                    String settingsStr = inbound.optString("settings", "");
                    if (!settingsStr.isEmpty()) {
                        try {
                            JSONObject settings = new JSONObject(settingsStr);
                            JSONArray clientsArr = settings.optJSONArray("clients");
                            if (clientsArr != null) {
                                for (int j = 0; j < clientsArr.length(); j++) {
                                    JSONObject c = clientsArr.getJSONObject(j);
                                    String email = c.optString("email");
                                    if (!email.isEmpty() && (!clients.containsKey(email) || clients.get(email) == 0)) {
                                        clients.put(email, c.optLong("expiryTime", 0));
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        log.info("Loaded {} unique clients from 3x-ui", clients.size());
        return clients;
    }

    public JSONObject getClientTraffic(String email) {
        return makeApiRequest("/panel/api/inbounds/getClientTraffics/" + email, "GET");
    }

    public boolean resetClientTraffic(String email) {
        List<Integer> inboundIds = findInboundIdsByEmail(email);
        if (inboundIds.isEmpty()) return false;
        boolean anySuccess = false;
        for (int inboundId : inboundIds) {
            JSONObject res = makeApiRequest("/panel/api/inbounds/" + inboundId + "/resetClientTraffic/" + email, "POST");
            if (res != null && res.optBoolean("success", false)) {
                anySuccess = true;
            }
        }
        return anySuccess;
    }

    public String getSubscriptionUrl(String email) {
        // Extract base domain (scheme + host only, no port/path)
        String baseUrl = panelUrl;
        if (baseUrl.contains("://")) {
            int schemeEnd = baseUrl.indexOf("://") + 3;
            int portColon = baseUrl.indexOf(":", schemeEnd);
            int firstSlash = baseUrl.indexOf("/", schemeEnd);
            int end = -1;
            if (portColon != -1 && (firstSlash == -1 || portColon < firstSlash)) {
                end = portColon;
            } else if (firstSlash != -1) {
                end = firstSlash;
            }
            if (end != -1) baseUrl = baseUrl.substring(0, end);
        }
        // Ensure path ends with /
        String path = subPath.endsWith("/") ? subPath : subPath + "/";
        return baseUrl + ":" + subPort + path + email;
    }


    public List<Integer> findInboundIdsByEmail(String email) {
        List<Integer> ids = new ArrayList<>();
        try {
            JSONObject res = makeApiRequest("/panel/api/inbounds/list", "GET");
            if (res != null && res.optBoolean("success")) {
                JSONArray arr = res.optJSONArray("obj");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject inbound = arr.getJSONObject(i);
                        JSONObject settings = new JSONObject(inbound.optString("settings", "{}"));
                        JSONArray clients = settings.optJSONArray("clients");
                        if (clients != null) {
                            for (int j = 0; j < clients.length(); j++) {
                                if (email.equals(clients.getJSONObject(j).optString("email"))) {
                                    ids.add(inbound.getInt("id"));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to find inbounds for email: {}", email);
        }
        return ids;
    }

    public List<String> getClientLinks(String email) {
        List<String> links = new ArrayList<>();
        String subUrl = getSubscriptionUrl(email);
        if (subUrl == null || subUrl.isEmpty()) return links;

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(subUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "curl/7.81.0");
            conn.setRequestProperty("Accept", "*/*");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String stringContent = br.lines().collect(java.util.stream.Collectors.joining(""));
                    try {
                        String decoded = new String(java.util.Base64.getMimeDecoder().decode(stringContent), StandardCharsets.UTF_8);
                        for (String line : decoded.split("\n")) {
                            String link = line.trim();
                            if (!link.isEmpty()) links.add(link);
                        }
                    } catch (IllegalArgumentException e) {
                        log.error("Failed to decode base64 from sub link for {}", email);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch sub links for {}", email, e);
        }
        return links;
    }

    public File downloadDb() {
        try {
            URL url = new URL(panelUrl + "/panel/api/server/getDb");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            if (authenticateConnection(conn)) {
                if (conn.getResponseCode() == 200) {
                    File tempFile = File.createTempFile("x-ui-backup-", ".db");
                    try (InputStream is = conn.getInputStream();
                         FileOutputStream fos = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    return tempFile;
                }
            }
        } catch (Exception e) {
            log.error("Failed to download DB from panel", e);
        }
        return null;
    }

    private JSONObject makeApiRequest(String path, String method) {
        try {
            URL url = new URL(panelUrl + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (authenticateConnection(conn)) {
                int code = conn.getResponseCode();
                if (code == 200) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        
                        String responseBody = sb.toString().trim();
                        if (responseBody.isEmpty()) {
                            return new JSONObject().put("success", true);
                        }
                        
                        try {
                            return new JSONObject(responseBody);
                        } catch (org.json.JSONException e) {
                            log.warn("API response for {} is not JSON: {}", path, responseBody);
                            if (responseBody.toLowerCase().contains("success")) {
                                return new JSONObject().put("success", true);
                            }
                            return null;
                        }
                    }
                } else {
                    log.warn("API error {}: {} {}", code, method, path);
                    sessionCookie = null;
                }
            }
        } catch (Exception e) {
            log.error("API request failed: {} {}", method, path, e);
        }
        return null;
    }

    private boolean authenticateConnection(HttpURLConnection conn) {
        if (apiToken != null && !apiToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            return true;
        } else {
            boolean loggedIn = (sessionCookie != null) || login();
            if (loggedIn) {
                conn.setRequestProperty("Cookie", sessionCookie);
            }
            return loggedIn;
        }
    }
}
