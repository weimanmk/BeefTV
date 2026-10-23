package main

import (
    "crypto/rand"
    "encoding/hex"
    "errors"
    "io"
    "mime"
    "net/http"
    "os"
    "path/filepath"
    "strings"
    "sync"
    "time"

    "github.com/gin-gonic/gin"
    "infinite-canvas/backend/internal/bootstrap"
)

type mobileExport struct {
    ID string `json:"id"`
    FileName string `json:"fileName"`
    MIME string `json:"mime"`
    State string `json:"state"`
    Error string `json:"error,omitempty"`
    Path string `json:"-"`
    Created time.Time `json:"-"`
}
type mobileExportStore struct {
    mu sync.Mutex
    dir string
    items map[string]*mobileExport
}
var mobileExports *mobileExportStore

func newMobileExportStore(dataDir string) (*mobileExportStore, error) {
    dir := filepath.Join(dataDir, "cache", "mobile-exports")
    if err := os.MkdirAll(dir, 0700); err != nil { return nil, err }
    // These are only disposable handoff files owned by this store.
    entries, _ := os.ReadDir(dir)
    for _, entry := range entries {
        if !entry.IsDir() && strings.HasPrefix(entry.Name(), "handoff-") { _ = os.Remove(filepath.Join(dir, entry.Name())) }
    }
    return &mobileExportStore{dir: dir, items: make(map[string]*mobileExport)}, nil
}

func (s *mobileExportStore) reserve() (*mobileExport, error) {
    s.mu.Lock()
    defer s.mu.Unlock()
    for id, item := range s.items {
        if time.Since(item.Created) > time.Hour {
            _ = os.Remove(item.Path)
            delete(s.items, id)
            continue
        }
        if item.State == "preparing" || item.State == "queued" || item.State == "awaiting" {
            return nil, errors.New("请先完成或取消当前导出")
        }
    }
    key := make([]byte, 16)
    if _, err := rand.Read(key); err != nil { return nil, err }
    file, err := os.CreateTemp(s.dir, "handoff-")
    if err != nil { return nil, err }
    _ = file.Close()
    item := &mobileExport{ID: hex.EncodeToString(key), State: "preparing", Path: file.Name(), Created: time.Now()}
    s.items[item.ID] = item
    return item, nil
}

func exportReply(c *gin.Context, code int, data any, message string) {
    c.Header("Cache-Control", "no-store")
    resultCode := code
    if code == 200 { resultCode = 0 }
    c.JSON(code, gin.H{"code": resultCode, "data": data, "msg": message})
}

func (s *mobileExportStore) create(c *gin.Context) {
    item, err := s.reserve()
    if err != nil { exportReply(c, 409, nil, err.Error()); return }
    success := false
    defer func() {
        if !success {
            s.mu.Lock()
            delete(s.items, item.ID)
            s.mu.Unlock()
            _ = os.Remove(item.Path)
        }
    }()
    c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 2<<30)
    fileName, mediaType := "", "application/octet-stream"
    if strings.HasPrefix(c.ContentType(), "multipart/") {
        reader, readErr := c.Request.MultipartReader()
        if readErr != nil { exportReply(c, 400, nil, "导出内容无效"); return }
        copied := false
        for {
            part, partErr := reader.NextPart()
            if partErr == io.EOF { break }
            if partErr != nil { exportReply(c, 400, nil, "导出上传未完成"); return }
            if part.FormName() == "file" && !copied {
                fileName = part.FileName()
                if parsed, _, parseErr := mime.ParseMediaType(part.Header.Get("Content-Type")); parseErr == nil { mediaType = parsed }
                output, openErr := os.OpenFile(item.Path, os.O_WRONLY|os.O_TRUNC, 0600)
                if openErr != nil { _ = part.Close(); exportReply(c, 500, nil, "无法暂存导出文件"); return }
                _, copyErr := io.Copy(output, part)
                closeErr := output.Close()
                if copyErr != nil || closeErr != nil { _ = part.Close(); exportReply(c, 500, nil, "导出文件未写入完成，请检查手机空间"); return }
                copied = true
            } else if part.FormName() == "fileName" {
                name, _ := io.ReadAll(io.LimitReader(part, 1024))
                fileName = string(name)
            }
            _ = part.Close()
        }
        if !copied { exportReply(c, 400, nil, "没有导出文件"); return }
    } else {
        var request struct { FileName string `json:"fileName"`; ResourceID string `json:"resourceId"` }
        if c.ShouldBindJSON(&request) != nil || request.ResourceID == "" || mobileRuntime == nil {
            exportReply(c, 400, nil, "导出素材无效"); return
        }
        if err := mobileRuntime.CopyOwnedResourceTo(request.ResourceID, item.Path); err != nil {
            exportReply(c, 400, nil, "无法读取当前工作区素材"); return
        }
        fileName = request.FileName
        if value := mime.TypeByExtension(filepath.Ext(fileName)); value != "" { mediaType, _, _ = mime.ParseMediaType(value) }
    }
    fileName = bootstrap.SanitizeSaveFileName(strings.ReplaceAll(fileName, "\\", "/"))
    if fileName == "" { fileName = "BeefTV-export" }
    s.mu.Lock()
    item.FileName, item.MIME, item.State = fileName, mediaType, "queued"
    result := *item
    s.mu.Unlock()
    success = true
    exportReply(c, 200, result, "")
}

func (s *mobileExportStore) handle(c *gin.Context) bool {
    root := "/api/mobile/exports"
    path := c.Request.URL.Path
    if path != root && !strings.HasPrefix(path, root+"/") { return false }
    if path == root {
        if c.Request.Method != http.MethodPost { exportReply(c, 405, nil, "不支持的方法"); return true }
        s.create(c)
        return true
    }
    suffix := strings.TrimPrefix(path, root+"/")
    if suffix == "next" && c.Request.Method == http.MethodGet {
        s.mu.Lock()
        var next *mobileExport
        for _, item := range s.items {
            if item.State == "queued" { item.State = "awaiting"; copy := *item; next = &copy; break }
        }
        s.mu.Unlock()
        exportReply(c, 200, next, "")
        return true
    }
    parts := strings.Split(suffix, "/")
    s.mu.Lock()
    item := s.items[parts[0]]
    if item == nil { s.mu.Unlock(); exportReply(c, 404, nil, "导出会话不存在或已过期"); return true }
    snapshot := *item
    s.mu.Unlock()
    if len(parts) == 1 && c.Request.Method == http.MethodGet { exportReply(c, 200, snapshot, ""); return true }
    if len(parts) == 2 && parts[1] == "file" && c.Request.Method == http.MethodGet {
        if snapshot.State != "awaiting" { exportReply(c, 409, nil, "当前导出不可读取"); return true }
        c.Header("Content-Type", snapshot.MIME)
        c.Header("Cache-Control", "no-store")
        c.FileAttachment(snapshot.Path, snapshot.FileName)
        return true
    }
    if len(parts) == 2 && parts[1] == "complete" && c.Request.Method == http.MethodPost {
        var result struct { State string `json:"state"`; Error string `json:"error"` }
        if c.ShouldBindJSON(&result) != nil || (result.State != "saved" && result.State != "cancelled" && result.State != "failed") {
            exportReply(c, 400, nil, "导出结果无效"); return true
        }
        s.mu.Lock()
        if item.State != "awaiting" && item.State != result.State {
            s.mu.Unlock(); exportReply(c, 409, nil, "导出状态已变更"); return true
        }
        item.State = result.State
        if result.State == "failed" { item.Error = "文件未保存成功，请检查手机空间和所选目录后重试" }
        snapshot = *item
        s.mu.Unlock()
        _ = os.Remove(snapshot.Path)
        exportReply(c, 200, snapshot, "")
        return true
    }
    exportReply(c, 405, nil, "不支持的方法")
    return true
}

