package main

import (
	"encoding/json"
	"errors"
	"log"
	"os"
	"strings"
	"sync"
	"sync/atomic"
)

type UserRecord struct {
	ID              int64  `json:"id"`
	Name            string `json:"name"`
	Banned          bool   `json:"banned"`
	XuiUsername     string `json:"xuiUsername"`
	ExpiryDate      int64  `json:"expiryDate"`
	LastNotifiedDay int    `json:"lastNotifiedDay"`
	TgUsername      string `json:"tgUsername"`
}

type fileStructure struct {
	Users []*UserRecord `json:"users"`
}

type Storage struct {
	mu       sync.RWMutex
	filePath string
	users    map[int64]*UserRecord
	dirty    atomic.Bool
}

func NewStorage(filePath string) *Storage {
	return &Storage{
		filePath: filePath,
		users:    make(map[int64]*UserRecord),
	}
}

func (s *Storage) Load() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	data, err := os.ReadFile(s.filePath)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}

	var root fileStructure
	if err := json.Unmarshal(data, &root); err != nil {
		return err
	}

	for _, u := range root.Users {
		if u.LastNotifiedDay == 0 && u.ExpiryDate == 0 {
			u.LastNotifiedDay = -1
		}
		s.users[u.ID] = u
	}

	log.Printf("Loaded %d users from %s", len(s.users), s.filePath)
	return nil
}

func (s *Storage) MarkDirty() {
	s.dirty.Store(true)
}

func (s *Storage) SaveIfDirty() {
	if s.dirty.Swap(false) {
		if err := s.Save(); err != nil {
			log.Printf("Save failed: %v", err)
		}
	}
}

func (s *Storage) Save() error {
	s.mu.RLock()
	defer s.mu.RUnlock()

	list := make([]*UserRecord, 0, len(s.users))
	for _, u := range s.users {
		list = append(list, u)
	}

	root := fileStructure{Users: list}
	data, err := json.MarshalIndent(root, "", "  ")
	if err != nil {
		return err
	}

	// Write via temp file for atomic replace
	tmpFile := s.filePath + ".tmp"
	if err := os.WriteFile(tmpFile, data, 0644); err != nil {
		return err
	}
	return os.Rename(tmpFile, s.filePath)
}

func (s *Storage) Get(id int64) (*UserRecord, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	u, ok := s.users[id]
	return u, ok
}

func (s *Storage) Set(u *UserRecord) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.users[u.ID] = u
	s.dirty.Store(true)
}

func (s *Storage) Delete(id int64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.users[id]; ok {
		delete(s.users, id)
		s.dirty.Store(true)
		return true
	}
	return false
}

func (s *Storage) All() []*UserRecord {
	s.mu.RLock()
	defer s.mu.RUnlock()
	res := make([]*UserRecord, 0, len(s.users))
	for _, u := range s.users {
		res = append(res, u)
	}
	return res
}

func (s *Storage) FindIDByUsername(username string) (int64, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	clean := strings.ToLower(strings.TrimSpace(username))
	if clean == "" {
		return 0, false
	}

	// First match by xuiUsername
	for _, u := range s.users {
		if strings.ToLower(strings.TrimSpace(u.XuiUsername)) == clean {
			return u.ID, true
		}
	}
	// Fallback match by Name
	for _, u := range s.users {
		if strings.ToLower(strings.TrimSpace(u.Name)) == clean {
			return u.ID, true
		}
	}
	return 0, false
}

func (s *Storage) IsUserBanned(id int64) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if u, ok := s.users[id]; ok {
		return u.Banned
	}
	return false
}

func (s *Storage) TotalAndBanned() (total int, active int, banned int) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	total = len(s.users)
	for _, u := range s.users {
		if u.Banned {
			banned++
		}
	}
	active = total - banned
	return
}
