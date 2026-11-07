package main

import (
	"bufio"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

type Config struct {
	BotToken           string
	BotUsername        string
	OwnerID            int64
	XuiPanelURL        string
	XuiPanelUsername   string
	XuiPanelPassword   string
	XuiAPIToken        string
	XuiSubPort         int
	XuiSubPath         string
	XuiCheckIntervalHr int
	XuiNotifyDays      []int
	XuiInboundPort     int
}

func LoadConfig() (*Config, error) {
	props := make(map[string]string)

	execDir := ""
	if execPath, err := os.Executable(); err == nil {
		execDir = filepath.Dir(execPath)
	}

	candidates := []string{
		"config.properties",
		filepath.Join(execDir, "config.properties"),
		"/bots/sendBot/config.properties",
		filepath.Join("src", "main", "resources", "config.properties"),
		filepath.Join(execDir, "src", "main", "resources", "config.properties"),
		"/bots/sendBot/src/main/resources/config.properties",
	}

	for _, path := range candidates {
		if path == "" {
			continue
		}
		if file, err := os.Open(path); err == nil {
			scanner := bufio.NewScanner(file)
			for scanner.Scan() {
				line := strings.TrimSpace(scanner.Text())
				if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
					continue
				}
				idx := strings.Index(line, "=")
				if idx != -1 {
					k := strings.TrimSpace(line[:idx])
					v := strings.TrimSpace(line[idx+1:])
					props[k] = v
				}
			}
			file.Close()
			break
		}
	}

	getVal := func(propKey, envKey, defVal string) string {
		if envVal := os.Getenv(envKey); envVal != "" {
			return envVal
		}
		if propVal, ok := props[propKey]; ok && propVal != "" {
			return propVal
		}
		return defVal
	}

	cfg := &Config{
		BotToken:         getVal("bot.token", "BOT_TOKEN", ""),
		BotUsername:      getVal("bot.username", "BOT_USERNAME", "TelegramUserBot"),
		XuiPanelURL:      getVal("xui.panel.url", "XUI_PANEL_URL", ""),
		XuiPanelUsername: getVal("xui.panel.username", "XUI_PANEL_USERNAME", ""),
		XuiPanelPassword: getVal("xui.panel.password", "XUI_PANEL_PASSWORD", ""),
		XuiAPIToken:      getVal("xui.api.token", "XUI_API_TOKEN", ""),
		XuiSubPath:       getVal("xui.sub.path", "XUI_SUB_PATH", "/sub/"),
	}

	if ownerStr := getVal("owner.id", "OWNER_ID", "0"); ownerStr != "" {
		cfg.OwnerID, _ = strconv.ParseInt(ownerStr, 10, 64)
	}

	subPortStr := getVal("xui.sub.port", "XUI_SUB_PORT", "10882")
	cfg.XuiSubPort, _ = strconv.Atoi(subPortStr)

	checkIntStr := getVal("xui.check.interval.hours", "XUI_CHECK_INTERVAL_HOURS", "6")
	cfg.XuiCheckIntervalHr, _ = strconv.Atoi(checkIntStr)
	if cfg.XuiCheckIntervalHr <= 0 {
		cfg.XuiCheckIntervalHr = 6
	}

	notifyDaysStr := getVal("xui.notify.days", "XUI_NOTIFY_DAYS", "3,1,0")
	for _, part := range strings.Split(notifyDaysStr, ",") {
		part = strings.TrimSpace(part)
		if part != "" {
			if d, err := strconv.Atoi(part); err == nil {
				cfg.XuiNotifyDays = append(cfg.XuiNotifyDays, d)
			}
		}
	}
	if len(cfg.XuiNotifyDays) == 0 {
		cfg.XuiNotifyDays = []int{3, 1, 0}
	}

	inboundPortStr := getVal("xui.inbound.port", "XUI_INBOUND_PORT", "")
	if inboundPortStr != "" {
		cfg.XuiInboundPort, _ = strconv.Atoi(inboundPortStr)
	}

	return cfg, nil
}
