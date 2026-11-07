package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	qrcode "github.com/skip2/go-qrcode"
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

func TestSearchAndExpiring(t *testing.T) {
	tmpDir := t.TempDir()
	path := filepath.Join(tmpDir, "users_exp.json")

	storage := NewStorage(path)
	now := time.Now().UnixMilli()

	storage.Set(&UserRecord{
		ID:          1001,
		Name:        "Alexey Ivanov",
		TgUsername:  "alex_tg",
		XuiUsername: "alex_xui",
		ExpiryDate:  now + 3*24*60*60*1000, // 3 days
	})
	storage.Set(&UserRecord{
		ID:          1002,
		Name:        "Dmitry",
		TgUsername:  "dmitry_tg",
		XuiUsername: "dmitry_xui",
		ExpiryDate:  now + 20*24*60*60*1000, // 20 days
	})

	// Test Search
	found := storage.Search("Alexey")
	if len(found) != 1 || found[0].ID != 1001 {
		t.Errorf("Search by name failed: %v", found)
	}

	found = storage.Search("1001")
	if len(found) != 1 || found[0].ID != 1001 {
		t.Errorf("Search by ID failed: %v", found)
	}

	found = storage.Search("@alex_tg")
	if len(found) != 1 || found[0].ID != 1001 {
		t.Errorf("Search by @username failed: %v", found)
	}

	// Test Expiring within 7 days
	expiring := storage.GetExpiring(7)
	if len(expiring) != 1 || expiring[0].ID != 1001 {
		t.Errorf("Expected 1 expiring user, got %d", len(expiring))
	}
}

func TestQRCodeGeneration(t *testing.T) {
	data := "https://example.com/sub/testuser"
	png, err := qrcode.Encode(data, qrcode.Medium, 256)
	if err != nil {
		t.Fatalf("QRCode encode failed: %v", err)
	}
	if len(png) < 100 {
		t.Errorf("QRCode PNG too small: %d bytes", len(png))
	}
}
