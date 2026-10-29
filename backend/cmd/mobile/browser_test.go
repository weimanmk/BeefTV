package main

import (
	"context"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"infinite-canvas/backend/internal/agentops"
	"infinite-canvas/backend/internal/bootstrap"
)

// Exercise the shipped Android frontend against its actual local HTTP boundary.
// The browser replaces the WebView only; no user database or model keys are used.
func TestMobileWorkspaceBrowser(t *testing.T) {
	if os.Getenv("BEEFTV_MOBILE_BROWSER") != "1" {
		t.Skip("requires production UI and Chromium")
	}
	dir := t.TempDir()
	ui, err := fs.Sub(bundledUI, "webui")
	if err != nil {
		t.Fatal(err)
	}
	index, err := fs.ReadFile(ui, "index.html")
	if err != nil || len(index) < 200 {
		t.Fatal("production frontend was not bundled")
	}
	if err := installOfficialPlugins(dir); err != nil {
		t.Fatal(err)
	}
	owner, err := agentops.EnsureOwnerToken(dir)
	if err != nil {
		t.Fatal(err)
	}
	previousExports, previousReferences, previousRuntime := mobileExports, mobileReferences, mobileRuntime
	defer func() {
		mobileExports, mobileReferences, mobileRuntime = previousExports, previousReferences, previousRuntime
	}()
	mobileExports, err = newMobileExportStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	mobileReferences = &mobileReferenceStore{dir: dir}
	rt, err := bootstrap.Open(context.Background(), bootstrap.Config{
		Profile: bootstrap.ProfileServer, DataDir: dir, ListenAddr: "127.0.0.1:18790", AutoMigrate: true,
		OpenExternalURL:  queueBrowserURL,
		RouterMiddleware: []gin.HandlerFunc{mobileHandler(ui, "browser-fixture-session", owner)},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rt.Close(context.Background()) }()
	if err := rt.Start(); err != nil {
		t.Fatal(err)
	}
	mobileRuntime = rt
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "node", "--test", "scripts/mobile-workspace-regression.mjs")
	cmd.Dir = filepath.Join("..", "..", "..", "web")
	cmd.Env = append(os.Environ(), "BEEFTV_MOBILE_BROWSER_ENABLED=1")
	output, err := cmd.CombinedOutput()
	t.Log(string(output))
	if err != nil {
		t.Fatalf("packaged mobile browser regression: %v", err)
	}
}
