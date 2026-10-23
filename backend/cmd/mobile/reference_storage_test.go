package main

import (
    "net/http"
    "net/http/httptest"
    "os"
    "path/filepath"
    "strings"
    "testing"
    "testing/fstest"
    "github.com/gin-gonic/gin"
)
func TestMobileReferenceStoragePrivateConfig(t *testing.T) {
    previous := mobileReferences
    dir := t.TempDir()
    mobileReferences = &mobileReferenceStore{dir:dir}
    defer func(){mobileReferences=previous}()
    router:=gin.New()
    router.Use(mobileHandler(fstest.MapFS{},"session","owner"))
    call:=func(method,cookie,origin,body string)*httptest.ResponseRecorder{
        req:=httptest.NewRequest(method,"http://127.0.0.1:18790/api/mobile/reference-storage",strings.NewReader(body))
        req.AddCookie(&http.Cookie{Name:"BeefTVSession",Value:cookie})
        req.Header.Set("Content-Type","application/json")
        req.Header.Set("Origin",origin)
        response:=httptest.NewRecorder();router.ServeHTTP(response,req);return response
    }
    payload:=`{"enabled":false,"accessKeyId":"fixture","secretAccessKey":"private-fixture-secret","region":"auto"}`
    if call("POST","","",payload).Code!=401 {t.Fatal("unauthorized config write")}
    if call("POST","session","https://other.test",payload).Code!=403 {t.Fatal("cross-origin config write")}
    response:=call("POST","session","",payload)
    if response.Code!=200 || strings.Contains(response.Body.String(),"private-fixture-secret"){t.Fatal("write failed or secret leaked")}
    response=call("GET","session","","")
    if response.Code!=200 || !strings.Contains(response.Body.String(),`"hasSecret":true`) || strings.Contains(response.Body.String(),"private-fixture-secret"){t.Fatal("public config invalid")}
    body,err:=os.ReadFile(filepath.Join(dir,"reference-storage.json"))
    if err!=nil || strings.Contains(string(body),"private-fixture-secret"){t.Fatal("secret stored unencrypted")}
    if call("POST","session","",`{"enabled":false,"accessKeyId":"fixture","secretAccessKey":""}`).Code!=200 {t.Fatal("empty secret did not preserve existing value")}
    response=call("GET","session","","")
    if !strings.Contains(response.Body.String(),`"hasSecret":true`){t.Fatal("saved secret lost")}
}

