package main

import (
    "net/http"
    "net/http/httptest"
    "testing"
    "testing/fstest"
    "strings"
    "github.com/gin-gonic/gin"
)

func TestMobileSessionAndStaticRouting(t *testing.T) {
    ui := fstest.MapFS{"index.html": {Data: []byte("mobile-ui")}, "static/app.js": {Data: []byte("javascript")}}
    router := gin.New()
    router.Use(mobileHandler(ui, "test-secret", "private-owner"))
    router.GET("/api/probe", func(c *gin.Context) { c.String(200, "api") })
    for _, test := range []struct{name, path, cookie, host, origin string; status int; body string}{
        {"unauthenticated", "/api/probe", "", "127.0.0.1:18790", "", 401, ""},
        {"wrong-token", "/", "wrong", "127.0.0.1:18790", "", 401, ""},
        {"wrong-host", "/", "test-secret", "attacker.test", "", 403, ""},
        {"cross-origin", "/api/probe", "test-secret", "127.0.0.1:18790", "https://attacker.test", 403, ""},
        {"api", "/api/probe", "test-secret", "127.0.0.1:18790", "http://127.0.0.1:18790", 200, "api"},
        {"ui", "/", "test-secret", "127.0.0.1:18790", "", 200, "mobile-ui"},
        {"spa-refresh", "/canvas/project-id", "test-secret", "127.0.0.1:18790", "", 200, "mobile-ui"},
        {"static", "/static/app.js", "test-secret", "127.0.0.1:18790", "", 200, "javascript"},
        {"missing-static", "/static/missing.js", "test-secret", "127.0.0.1:18790", "", 404, ""},
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

func TestMobileManualPrincipalRequiresSessionAndExactOperation(t *testing.T) {
    router := gin.New()
    router.Use(mobileHandler(fstest.MapFS{}, "session", "private-owner"))
    router.Any("/api/ops/:op", func(c *gin.Context) {
        if c.GetHeader("X-Beeftv-Owner") == "private-owner" { c.String(200, "manual") } else { c.String(200, "untrusted") }
    })
    for _, test := range []struct{method, op, session, origin, body string; status int}{
        {"POST", "canvas.document.commit", "session", "http://127.0.0.1:18790", "manual", 200},
        {"POST", "canvas.task.bind", "session", "", "manual", 200},
        {"POST", "conversation.message.attach", "session", "", "manual", 200},
        {"POST", "canvas.document.commit", "", "", "", 401},
        {"POST", "canvas.document.commit", "wrong", "", "", 401},
        {"POST", "canvas.document.commit", "session", "https://other.test", "", 403},
        {"POST", "clients", "session", "", "untrusted", 200},
        {"POST", "canvas.nodes.create", "session", "", "untrusted", 200},
        {"GET", "canvas.document.commit", "session", "", "untrusted", 200},
    } {
        t.Run(test.method+"/"+test.op+"/"+test.session+"/"+test.origin, func(t *testing.T) {
            req := httptest.NewRequest(test.method, "http://127.0.0.1:18790/api/ops/"+test.op, strings.NewReader("{}"))
            req.AddCookie(&http.Cookie{Name: "BeefTVSession", Value: test.session})
            req.Header.Set("Origin", test.origin)
            req.Header.Set("X-Beeftv-Owner", "forged")
            response := httptest.NewRecorder()
            router.ServeHTTP(response, req)
            if response.Code != test.status || (test.body != "" && response.Body.String() != test.body) {
                t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
            }
        })
    }
}
