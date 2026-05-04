package com.example;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
    private final int inboundPort; // -1 means all inbounds
    private String sessionCookie;
    
    public XuiApiClient(String panelUrl, String username, String password, int inboundPort) {
        this.panelUrl = panelUrl.endsWith("/") ? panelUrl.substring(0, panelUrl.length() - 1) : panelUrl;
        this.username = username;
        this.password = password;
        this.inboundPort = inboundPort;
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
        
        try {
            if (sessionCookie == null && !login()) {
                log.error("Cannot login to 3x-ui panel");
                return clients;
            }
            
            URL url = new URL(panelUrl + "/panel/api/inbounds/list");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Cookie", sessionCookie);
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                JSONObject json = new JSONObject(response.toString());
                if (json.getBoolean("success")) {
                    JSONArray arr = json.getJSONArray("obj");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        // Filter by port if configured
                        if (inboundPort != -1 && item.optInt("port", -1) != inboundPort) {
                            continue;
                        }
                        if (item.has("clientStats")) {
                            JSONArray clientStats = item.getJSONArray("clientStats");
                            for (int j = 0; j < clientStats.length(); j++) {
                                JSONObject client = clientStats.getJSONObject(j);
                                clients.put(client.getString("email"), client.optLong("expiryTime", 0L));
                            }
                        } else if (item.has("email")) {
                            clients.put(item.getString("email"), item.optLong("expiryTime", 0L));
                        }
                    }
                    log.info("Loaded {} clients from 3x-ui (port filter: {})", clients.size(),
                             inboundPort == -1 ? "all" : inboundPort);
                }
            } else {
                log.warn("Failed to get clients, response code: {}", responseCode);
                // Try to re-login
                sessionCookie = null;
            }
        } catch (Exception e) {
            log.error("Error getting clients: {}", e.getMessage());
        }
        
        return clients;
    }
}
