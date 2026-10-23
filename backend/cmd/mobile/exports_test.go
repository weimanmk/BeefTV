package main

import (
    "bytes"
    "encoding/json"
    "io"
    "mime/multipart"
    "net/http"
    "net/http/httptest"
    "os"
    "strings"
    "testing"
    "testing/fstest"

    "github.com/gin-gonic/gin"
)

func TestMobileExportHandoffAndReceipts(t *testing.T) {
    previous := mobileExports
    var err error
    mobileExports, err = newMobileExportStore(t.TempDir())
    if err != nil { t.Fatal(err) }
    defer func() { mobileExports = previous }()
    router := gin.New()
    router.Use(mobileHandler(fstest.MapFS{}, "session", "owner"))
    call := func(method, path, cookie, origin, contentType string, body io.Reader) *httptest.ResponseRecorder {
        req := httptest.NewRequest(method, "http://127.0.0.1:18790/api/mobile/exports"+path, body)
        req.AddCookie(&http.Cookie{Name:"BeefTVSession", Value:cookie})
        req.Header.Set("Origin", origin)
        req.Header.Set("Content-Type", contentType)
        response := httptest.NewRecorder()
        router.ServeHTTP(response, req)
        return response
    }
    upload := func() *httptest.ResponseRecorder {
        var body bytes.Buffer
        writer := multipart.NewWriter(&body)
        part, _ := writer.CreateFormFile("file", "blob")
        _, _ = part.Write([]byte("字幕文件\nbytes-exact"))
        _ = writer.WriteField("fileName", "../镜头字幕.srt")
        _ = writer.Close()
        return call("POST", "", "session", "", writer.FormDataContentType(), &body)
    }
    response := upload()
    if response.Code != 200 { t.Fatal(response.Body.String()) }
    var created struct { Data mobileExport `json:"data"` }
    if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil { t.Fatal(err) }
    id := created.Data.ID
    if created.Data.FileName != "镜头字幕.srt" || created.Data.State != "queued" { t.Fatalf("bad name/state: %+v", created.Data) }
    if upload().Code != 409 { t.Fatal("concurrent export accepted") }
    if call("GET", "/next", "", "", "", nil).Code != 401 { t.Fatal("unauthenticated handoff") }
    if call("GET", "/next", "session", "https://other.test", "", nil).Code != 403 { t.Fatal("cross-origin handoff") }
    if call("POST", "/"+id+"/complete", "session", "", "application/json", strings.NewReader(`{"state":"saved"}`)).Code != 409 { t.Fatal("saved before native handoff") }
    response = call("GET", "/next", "session", "", "", nil)
    if !strings.Contains(response.Body.String(), id) { t.Fatal("handoff lost") }
    response = call("GET", "/"+id+"/file", "session", "", "", nil)
    if response.Code != 200 || response.Body.String() != "字幕文件\nbytes-exact" { t.Fatalf("file bytes changed: %s", response.Body.String()) }
    for _, state := range []string{"awaiting", "saved"} {
        if state == "saved" {
            response = call("POST", "/"+id+"/complete", "session", "", "application/json", strings.NewReader(`{"state":"saved"}`))
            if response.Code != 200 { t.Fatal(response.Body.String()) }
        }
        response = call("GET", "/"+id, "session", "", "", nil)
        if !strings.Contains(response.Body.String(), `"state":"`+state+`"`) { t.Fatal(response.Body.String()) }
    }
    if _, err := os.Stat(mobileExports.items[id].Path); !os.IsNotExist(err) { t.Fatal("handoff file not removed") }
    response = upload()
    if response.Code != 200 { t.Fatal("new export blocked after saved") }
    _ = json.Unmarshal(response.Body.Bytes(), &created)
    id = created.Data.ID
    call("GET", "/next", "session", "", "", nil)
    response = call("POST", "/"+id+"/complete", "session", "", "application/json", strings.NewReader(`{"state":"cancelled"}`))
    if response.Code != 200 || !strings.Contains(response.Body.String(), `"state":"cancelled"`) { t.Fatal("cancellation lost") }
    if call("GET", "/"+id+"/file", "session", "", "", nil).Code != 409 { t.Fatal("cancelled file still readable") }
}

