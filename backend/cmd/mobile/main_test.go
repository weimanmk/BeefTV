package main

import (
    "net/http"
    "net/http/httptest"
    "testing"
    "testing/fstest"
    "github.com/gin-gonic/gin"
)

func TestMobileSessionAndStaticRouting(t *testing.T) {
    ui := fstest.MapFS{"index.html": {Data: []byte("mobile-ui")}, "static/app.js": {Data: []byte("javascript")}}
    router := gin.New()
    router.Use(mobileHandler(ui, "test-secret"))
    router.GET("/api/probe", func(c *gin.Context) { c.String(200, "api") })
    for _, test := range []struct{name, path, cookie, host, origin string; status int; body string}{
        {"unauthenticated", "/api/probe", "", "127.0.0.1:18789", "", 401, ""},
        {"wrong-token", "/", "wrong", "127.0.0.1:18789", "", 401, ""},
        {"wrong-host", "/", "test-secret", "attacker.test", "", 403, ""},
        {"cross-origin", "/api/probe", "test-secret", "127.0.0.1:18789", "https://attacker.test", 403, ""},
        {"api", "/api/probe", "test-secret", "127.0.0.1:18789", "http://127.0.0.1:18789", 200, "api"},
        {"ui", "/", "test-secret", "127.0.0.1:18789", "", 200, "mobile-ui"},
        {"spa-refresh", "/canvas/project-id", "test-secret", "127.0.0.1:18789", "", 200, "mobile-ui"},
        {"static", "/static/app.js", "test-secret", "127.0.0.1:18789", "", 200, "javascript"},
        {"missing-static", "/static/missing.js", "test-secret", "127.0.0.1:18789", "", 404, ""},
    } {
        t.Run(test.name, func(t *testing.T) {
            req := httptest.NewRequest(http.MethodGet, "http://"+test.host+test.path, nil)
            if test.cookie != "" { req.AddCookie(&http.Cookie{Name: "BeefTVSession", Value: test.cookie}) }
            req.Header.Set("Origin", test.origin)
            response := httptest.NewRecorder()
            router.ServeHTTP(response, req)
            if response.Code != test.status { t.Fatalf("status = %d, want %d; %s", response.Code, test.status, response.Body.String()) }
            if test.body != "" && response.Body.String() != test.body { t.Fatalf("body = %q", response.Body.String()) }
        })
    }
}
