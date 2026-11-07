package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestConfigLoadWithEnv(t *testing.T) {
	t.Setenv("BOT_TOKEN", "123456:ABC-DEF")
	t.Setenv("OWNER_ID", "999888777")
	t.Setenv("XUI_SUB_PORT", "10443")
	t.Setenv("XUI_NOTIFY_DAYS", "5,2,1")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}

	if cfg.BotToken != "123456:ABC-DEF" {
		t.Errorf("Expected bot.token from env, got %s", cfg.BotToken)
	}
	if cfg.OwnerID != 999888777 {
		t.Errorf("Expected owner.id from env, got %d", cfg.OwnerID)
	}
	if cfg.XuiSubPort != 10443 {
		t.Errorf("Expected xui.sub.port 10443, got %d", cfg.XuiSubPort)
	}
	if len(cfg.XuiNotifyDays) != 3 || cfg.XuiNotifyDays[0] != 5 {
		t.Errorf("Expected notify days [5,2,1], got %v", cfg.XuiNotifyDays)
	}
}

func TestStorageLoadAndFind(t *testing.T) {
	tmpDir := t.TempDir()
	path := filepath.Join(tmpDir, "users.json")

	content := `{
  "users": [
    {
      "id": 1941903357,
      "name": "Kirabas",
      "banned": false,
      "xuiUsername": ""
    },
    {
      "id": 5516363342,
      "name": "Admin",
      "banned": false,
      "xuiUsername": "Ruslan"
    },
    {
      "id": 546253132,
      "name": "BannedUser",
      "banned": true,
      "xuiUsername": ""
    }
  ]
}`
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatalf("Failed to write test file: %v", err)
	}

	storage := NewStorage(path)
	if err := storage.Load(); err != nil {
		t.Fatalf("Storage.Load failed: %v", err)
	}

	total, active, banned := storage.TotalAndBanned()
	if total != 3 || active != 2 || banned != 1 {
		t.Fatalf("Expected 3 users (2 active, 1 banned), got total=%d, active=%d, banned=%d", total, active, banned)
	}

	// Test find by name
	id, found := storage.FindIDByUsername("Kirabas")
	if !found || id != 1941903357 {
		t.Errorf("FindIDByUsername(Kirabas) failed, got id=%d, found=%v", id, found)
	}

	// Test find by xuiUsername
	id, found = storage.FindIDByUsername("Ruslan")
	if !found || id != 5516363342 {
		t.Errorf("FindIDByUsername(Ruslan) failed, got id=%d, found=%v", id, found)
	}

	// Test case-insensitivity
	id, found = storage.FindIDByUsername("kirabas")
	if !found || id != 1941903357 {
		t.Errorf("FindIDByUsername case insensitive failed")
	}
}

func TestFormatTraffic(t *testing.T) {
	tests := []struct {
		bytes    int64
		expected string
	}{
		{0, "0 B"},
		{500, "500 B"},
		{1024, "1.00 KB"},
		{1048576, "1.00 MB"},
		{1073741824, "1.00 GB"},
		{2147483648, "2.00 GB"},
	}

	for _, tc := range tests {
		actual := formatTraffic(tc.bytes)
		if actual != tc.expected {
			t.Errorf("formatTraffic(%d): expected %s, got %s", tc.bytes, tc.expected, actual)
		}
	}
}

func TestSubscriptionURL(t *testing.T) {
	client := NewXuiClient(
		"https://germany.ohnereal.ru:3/NjNjGjBClSuJ2hK/",
		"user", "pass", "token",
		10443,
		"/sub/SeHCXRiF1IDTkt6vw9/",
		443,
	)

	subURL := client.GetSubscriptionURL("testuser")
	expected := "https://germany.ohnereal.ru:10443/sub/SeHCXRiF1IDTkt6vw9/testuser"
	if subURL != expected {
		t.Errorf("GetSubscriptionURL: expected %s, got %s", expected, subURL)
	}
}

func TestSaveAndReloadAtomic(t *testing.T) {
	tmpDir := t.TempDir()
	path := filepath.Join(tmpDir, "test_users.json")

	storage := NewStorage(path)
	storage.Set(&UserRecord{
		ID:          123,
		Name:        "Tester",
		Banned:      false,
		XuiUsername: "tester_vpn",
	})

	if err := storage.Save(); err != nil {
		t.Fatalf("Save failed: %v", err)
	}

	storage2 := NewStorage(path)
	if err := storage2.Load(); err != nil {
		t.Fatalf("Reload failed: %v", err)
	}

	u, ok := storage2.Get(123)
	if !ok || u.Name != "Tester" || u.XuiUsername != "tester_vpn" {
		t.Errorf("User mismatch on reload: %+v", u)
	}
}
