package com.example;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class XuiApiClient {
    private static final Logger log = LoggerFactory.getLogger(XuiApiClient.class);
    private final String panelUrl;
    private final String username;
    private final String password;
    private final String apiToken;
    private String sessionCookie;
    
    public XuiApiClient(String panelUrl, String username, String password, String apiToken) {
        this.panelUrl = panelUrl.endsWith("/") ? panelUrl.substring(0, panelUrl.length() - 1) : panelUrl;
        this.username = username;
        this.password = password;
        this.apiToken = apiToken;
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
        int inboundId = findInboundIdByEmail(email);
        if (inboundId == -1) return false;
        JSONObject res = makeApiRequest("/panel/api/inbounds/" + inboundId + "/resetClientTraffic/" + email, "POST");
        return res != null && res.optBoolean("success", false);
    }

    public String getSubscriptionUrl(String email) {
        String subId = findSubIdByEmail(email);
        if (subId == null || subId.isEmpty()) return null;
        return panelUrl + "/sub/" + subId;
    }

    public String findSubIdByEmail(String email) {
        try {
            JSONObject res = makeApiRequest("/panel/api/inbounds/list", "GET");
            if (res != null && res.optBoolean("success")) {
                JSONArray arr = res.optJSONArray("obj");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject inbound = arr.getJSONObject(i);
                        String settingsStr = inbound.optString("settings", "");
                        if (!settingsStr.isEmpty()) {
                            JSONObject settings = new JSONObject(settingsStr);
                            JSONArray clients = settings.optJSONArray("clients");
                            if (clients != null) {
                                for (int j = 0; j < clients.length(); j++) {
                                    JSONObject client = clients.getJSONObject(j);
                                    if (email.equals(client.optString("email"))) {
                                        return client.optString("subId", "");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to find subId for email: {}", email);
        }
        return null;
    }

    public int findInboundIdByEmail(String email) {
        try {
            JSONObject res = makeApiRequest("/panel/api/inbounds/list", "GET");
            if (res != null && res.optBoolean("success")) {
                JSONArray arr = res.optJSONArray("obj");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject inbound = arr.getJSONObject(i);
                        JSONArray clientStats = inbound.optJSONArray("clientStats");
                        if (clientStats != null) {
                            for (int j = 0; j < clientStats.length(); j++) {
                                if (email.equals(clientStats.getJSONObject(j).optString("email"))) {
                                    return inbound.getInt("id");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to find inbound for email: {}", email);
        }
        return -1;
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
