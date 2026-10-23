package main

/*
#include <stdlib.h>
*/
import "C"

import (
    "context"
    "crypto/rand"
    "crypto/sha256"
    "crypto/subtle"
    "embed"
    "encoding/hex"
    "encoding/json"
    "io/fs"
    "net/http"
    "os"
    "path/filepath"
    "strings"
    "sync"

    "github.com/gin-gonic/gin"
    "infinite-canvas/backend/internal/bootstrap"
)

// The workflow replaces this placeholder with the actual Vite production build.
//go:embed all:webui all:officialplugins
var bundledUI embed.FS
var runtimeMu sync.Mutex
var mobileRuntime *bootstrap.Runtime
var sessionJSON string

//export StartMobile
func StartMobile(dataDir *C.char) *C.char {
    runtimeMu.Lock()
    defer runtimeMu.Unlock()
    if mobileRuntime != nil { return C.CString(sessionJSON) }
    result := func() (map[string]string, error) {
        secret := make([]byte, 32)
        if _, err := rand.Read(secret); err != nil { return nil, err }
        token := hex.EncodeToString(secret)
        directory := C.GoString(dataDir)
        if err := installOfficialPlugins(directory); err != nil { return nil, err }
        ui, err := fs.Sub(bundledUI, "webui")
        if err != nil { return nil, err }
        rt, err := bootstrap.Open(context.Background(), bootstrap.Config{
            Profile: bootstrap.ProfileServer,
            DataDir: directory,
            // Stable origin keeps IndexedDB and UI preferences across launches.
            ListenAddr: "127.0.0.1:18789",
            AutoMigrate: true,
            RouterMiddleware: []gin.HandlerFunc{mobileHandler(ui, token)},
        })
        if err != nil { return nil, err }
        if err := rt.Start(); err != nil { _ = rt.Close(context.Background()); return nil, err }
        mobileRuntime = rt
        return map[string]string{"url": strings.TrimSuffix(rt.BaseURL(), "/api"), "token": token}, nil
    }
    value, err := result()
    if err != nil { value = map[string]string{"error": err.Error()} }
    encoded, _ := json.Marshal(value)
    sessionJSON = string(encoded)
    return C.CString(sessionJSON)
}

func mobileHandler(ui fs.FS, token string) gin.HandlerFunc {
    files := http.FileServer(http.FS(ui))
    return func(c *gin.Context) {
        cookie, _ := c.Cookie("BeefTVSession")
        if subtle.ConstantTimeCompare([]byte(cookie), []byte(token)) != 1 {
            c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "手机会话无效"})
            return
        }
        // Reject other browser origins even if they somehow acquired a cookie.
        origin := c.GetHeader("Origin")
        if c.Request.Host != "127.0.0.1:18789" || (origin != "" && origin != "http://127.0.0.1:18789") {
            c.AbortWithStatus(http.StatusForbidden)
            return
        }
        if c.Request.URL.Path == "/api" || strings.HasPrefix(c.Request.URL.Path, "/api/") { c.Next(); return }
        if c.Request.Method != http.MethodGet && c.Request.Method != http.MethodHead { c.AbortWithStatus(405); return }
        c.Header("X-Content-Type-Options", "nosniff")
        c.Header("Cross-Origin-Opener-Policy", "same-origin")
        name := strings.TrimPrefix(c.Request.URL.Path, "/")
        if name == "" { name = "index.html" }
        if info, err := fs.Stat(ui, name); err != nil || info.IsDir() {
            if strings.Contains(name, ".") || strings.HasPrefix(name, "static/") { c.AbortWithStatus(404); return }
            c.Request.URL.Path = "/"
        }
        files.ServeHTTP(c.Writer, c.Request)
        c.Abort()
    }
}

func installOfficialPlugins(dataDir string) error {
    entries, err := fs.ReadDir(bundledUI, "officialplugins")
    if err != nil { return err }
    packages := make(map[string][]byte)
    fingerprint := sha256.New()
    for _, entry := range entries {
        if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".beeftv-plugin") { continue }
        data, err := bundledUI.ReadFile("officialplugins/" + entry.Name())
        if err != nil { return err }
        packages[entry.Name()] = data
        fingerprint.Write([]byte(entry.Name()))
        fingerprint.Write(data)
    }
    destination := filepath.Join(dataDir, "mobile-runtime", "plugins-" + hex.EncodeToString(fingerprint.Sum(nil)[:8]))
    if err := os.MkdirAll(destination, 0o700); err != nil { return err }
    for name, data := range packages {
        if err := os.WriteFile(filepath.Join(destination, name), data, 0o600); err != nil { return err }
    }
    return os.Setenv("CANVAS_OFFICIAL_PLUGIN_DIR", destination)
}

func main() {}
