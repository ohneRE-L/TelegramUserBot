package main

import (
	"bytes"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

type XuiClient struct {
	mu            sync.Mutex
	panelURL      string
	username      string
	password      string
	apiToken      string
	subPort       int
	subPath       string
	inboundPort   int
	client        *http.Client
	sessionCookie string
}

func NewXuiClient(panelURL, username, password, apiToken string, subPort int, subPath string, inboundPort int) *XuiClient {
	jar, _ := cookiejar.New(nil)
	cleanURL := strings.TrimRight(panelURL, "/")
	cleanSubPath := subPath
	if !strings.HasPrefix(cleanSubPath, "/") {
		cleanSubPath = "/" + cleanSubPath
	}
	if !strings.HasSuffix(cleanSubPath, "/") {
		cleanSubPath += "/"
	}

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
	}

	return &XuiClient{
		panelURL:    cleanURL,
		username:    username,
		password:    password,
		apiToken:    apiToken,
		subPort:     subPort,
		subPath:     cleanSubPath,
		inboundPort: inboundPort,
		client: &http.Client{
			Jar:       jar,
			Transport: tr,
			Timeout:   15 * time.Second,
		},
	}
}

func (x *XuiClient) Login() error {
	loginURL := x.panelURL + "/login"
	bodyData := map[string]string{
		"username": x.username,
		"password": x.password,
	}
	payload, _ := json.Marshal(bodyData)

	req, err := http.NewRequest("POST", loginURL, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := x.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("login failed with status %d", resp.StatusCode)
	}

	for _, c := range resp.Cookies() {
		if strings.Contains(strings.ToLower(c.Name), "session") || strings.Contains(strings.ToLower(c.Name), "x-ui") || strings.Contains(strings.ToLower(c.Name), "3x-ui") {
			x.sessionCookie = c.Name + "=" + c.Value
			break
		}
	}
	if x.sessionCookie == "" && len(resp.Cookies()) > 0 {
		x.sessionCookie = resp.Cookies()[0].Name + "=" + resp.Cookies()[0].Value
	}

	log.Printf("Successfully logged into 3x-ui panel")
	return nil
}

func (x *XuiClient) makeRequest(method, path string, body io.Reader) ([]byte, error) {
	reqURL := x.panelURL + path
	req, err := http.NewRequest(method, reqURL, body)
	if err != nil {
		return nil, err
	}

	if x.apiToken != "" {
		req.Header.Set("Authorization", "Bearer "+x.apiToken)
	} else {
		x.mu.Lock()
		if x.sessionCookie == "" {
			if err := x.Login(); err != nil {
				x.mu.Unlock()
				return nil, err
			}
		}
		if x.sessionCookie != "" {
			req.Header.Set("Cookie", x.sessionCookie)
		}
		x.mu.Unlock()
	}

	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := x.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		if x.apiToken == "" {
			x.mu.Lock()
			x.sessionCookie = ""
			_ = x.Login()
			if x.sessionCookie != "" {
				req, _ = http.NewRequest(method, reqURL, body)
				req.Header.Set("Cookie", x.sessionCookie)
				x.mu.Unlock()
				resp2, err2 := x.client.Do(req)
				if err2 == nil {
					defer resp2.Body.Close()
					return io.ReadAll(resp2.Body)
				}
			} else {
				x.mu.Unlock()
			}
		}
		return nil, fmt.Errorf("unauthorized response %d", resp.StatusCode)
	}

	return io.ReadAll(resp.Body)
}

type inboundRaw struct {
	ID          int    `json:"id"`
	Port        int    `json:"port"`
	Settings    string `json:"settings"`
	ClientStats []struct {
		Email      string `json:"email"`
		ExpiryTime int64  `json:"expiryTime"`
	} `json:"clientStats"`
}

type settingsClients struct {
	Clients []struct {
		Email      string `json:"email"`
		ExpiryTime int64  `json:"expiryTime"`
	} `json:"clients"`
}

func (x *XuiClient) GetAllClientsExpiry() (map[string]int64, error) {
	data, err := x.makeRequest("GET", "/panel/api/inbounds/list", nil)
	if err != nil {
		return nil, err
	}

	var res struct {
		Success bool         `json:"success"`
		Obj     []inboundRaw `json:"obj"`
	}

	if err := json.Unmarshal(data, &res); err != nil {
		return nil, err
	}

	clients := make(map[string]int64)
	for _, in := range res.Obj {
		if x.inboundPort > 0 && in.Port != x.inboundPort {
			continue
		}

		// 1. Scan clientStats
		for _, s := range in.ClientStats {
			if s.Email != "" {
				clients[s.Email] = s.ExpiryTime
			}
		}

		// 2. Scan settings clients
		if in.Settings != "" {
			var sc settingsClients
			if err := json.Unmarshal([]byte(in.Settings), &sc); err == nil {
				for _, c := range sc.Clients {
					if c.Email != "" {
						if cur, ok := clients[c.Email]; !ok || cur == 0 {
							clients[c.Email] = c.ExpiryTime
						}
					}
				}
			}
		}
	}

	log.Printf("Loaded %d unique clients from 3x-ui", len(clients))
	return clients, nil
}

func (x *XuiClient) GetClientTraffic(email string) (up int64, down int64, err error) {
	data, err := x.makeRequest("GET", "/panel/api/inbounds/getClientTraffics/"+url.PathEscape(email), nil)
	if err != nil {
		return 0, 0, err
	}

	var res struct {
		Success bool `json:"success"`
		Obj     struct {
			Up   int64 `json:"up"`
			Down int64 `json:"down"`
		} `json:"obj"`
	}

	if err := json.Unmarshal(data, &res); err != nil {
		return 0, 0, err
	}

	return res.Obj.Up, res.Obj.Down, nil
}

func (x *XuiClient) FindInboundIDsByEmail(email string) ([]int, error) {
	data, err := x.makeRequest("GET", "/panel/api/inbounds/list", nil)
	if err != nil {
		return nil, err
	}

	var res struct {
		Success bool         `json:"success"`
		Obj     []inboundRaw `json:"obj"`
	}

	if err := json.Unmarshal(data, &res); err != nil {
		return nil, err
	}

	var ids []int
	for _, in := range res.Obj {
		if in.Settings != "" {
			var sc settingsClients
			if err := json.Unmarshal([]byte(in.Settings), &sc); err == nil {
				for _, c := range sc.Clients {
					if c.Email == email {
						ids = append(ids, in.ID)
						break
					}
				}
			}
		}
	}
	return ids, nil
}

func (x *XuiClient) ResetClientTraffic(email string) bool {
	ids, err := x.FindInboundIDsByEmail(email)
	if err != nil || len(ids) == 0 {
		return false
	}

	anySuccess := false
	for _, id := range ids {
		path := fmt.Sprintf("/panel/api/inbounds/%d/resetClientTraffic/%s", id, url.PathEscape(email))
		data, err := x.makeRequest("POST", path, nil)
		if err == nil {
			var res struct {
				Success bool `json:"success"`
			}
			if json.Unmarshal(data, &res) == nil && res.Success {
				anySuccess = true
			}
		}
	}
	return anySuccess
}

func (x *XuiClient) GetSubscriptionURL(email string) string {
	u, err := url.Parse(x.panelURL)
	if err != nil {
		return ""
	}
	baseURL := fmt.Sprintf("%s://%s", u.Scheme, u.Hostname())
	return fmt.Sprintf("%s:%d%s%s", baseURL, x.subPort, x.subPath, email)
}

func (x *XuiClient) GetClientLinks(email string) ([]string, error) {
	subURL := x.GetSubscriptionURL(email)
	if subURL == "" {
		return nil, fmt.Errorf("empty subscription url")
	}

	req, err := http.NewRequest("GET", subURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "curl/7.81.0")
	req.Header.Set("Accept", "*/*")

	resp, err := x.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("sub status %d", resp.StatusCode)
	}

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	trimmed := strings.TrimSpace(string(raw))
	decodedBytes, err := base64.StdEncoding.DecodeString(trimmed)
	var content string
	if err != nil {
		decodedBytes, err = base64.RawStdEncoding.DecodeString(trimmed)
	}
	if err == nil {
		content = string(decodedBytes)
	} else {
		content = trimmed
	}

	lines := strings.Split(content, "\n")
	var links []string
	for _, line := range lines {
		l := strings.TrimSpace(line)
		if l != "" {
			links = append(links, l)
		}
	}
	return links, nil
}

func (x *XuiClient) DownloadDB() (string, error) {
	data, err := x.makeRequest("GET", "/panel/api/server/getDb", nil)
	if err != nil {
		return "", err
	}

	tmpFile, err := os.CreateTemp("", "x-ui-backup-*.db")
	if err != nil {
		return "", err
	}
	defer tmpFile.Close()

	if _, err := tmpFile.Write(data); err != nil {
		_ = os.Remove(tmpFile.Name())
		return "", err
	}

	return tmpFile.Name(), nil
}

type ServerStatus struct {
	CPU         float64
	MemCurrent  int64
	MemTotal    int64
	DiskCurrent int64
	DiskTotal   int64
	XrayState   string
	XrayVersion string
	Uptime      int64
}

func (x *XuiClient) GetServerStatus() (*ServerStatus, error) {
	data, err := x.makeRequest("POST", "/server/status", nil)
	if err != nil {
		data, err = x.makeRequest("GET", "/panel/api/server/status", nil)
	}
	if err != nil {
		return nil, err
	}

	var res struct {
		Success bool `json:"success"`
		Obj     struct {
			CPU  float64 `json:"cpu"`
			Mem  struct {
				Current int64 `json:"current"`
				Total   int64 `json:"total"`
			} `json:"mem"`
			Disk struct {
				Current int64 `json:"current"`
				Total   int64 `json:"total"`
			} `json:"disk"`
			Xray struct {
				State   string `json:"state"`
				Version string `json:"version"`
			} `json:"xray"`
			Uptime int64 `json:"uptime"`
		} `json:"obj"`
	}

	if err := json.Unmarshal(data, &res); err != nil {
		return nil, err
	}

	return &ServerStatus{
		CPU:         res.Obj.CPU,
		MemCurrent:  res.Obj.Mem.Current,
		MemTotal:    res.Obj.Mem.Total,
		DiskCurrent: res.Obj.Disk.Current,
		DiskTotal:   res.Obj.Disk.Total,
		XrayState:   res.Obj.Xray.State,
		XrayVersion: res.Obj.Xray.Version,
		Uptime:      res.Obj.Uptime,
	}, nil
}
