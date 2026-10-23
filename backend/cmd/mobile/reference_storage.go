package main

import (
    "context"
    "fmt"
    "net/http"
    "os"
    "path/filepath"
    "strconv"
    "strings"
    "sync"
    "time"

    "github.com/gin-gonic/gin"
    "infinite-canvas/backend/internal/referencestorage"
)

// This adapter reuses desktop S3 storage and ownership checks without exposing
// a Wails bridge, arbitrary file paths, or the saved secret to JavaScript.
type mobileReferenceStore struct { dir string; mu sync.Mutex }
var mobileReferences *mobileReferenceStore

func (s *mobileReferenceStore) handle(c *gin.Context) bool {
    root := "/api/mobile/reference-storage"
    path := c.Request.URL.Path
    if path != root && !strings.HasPrefix(path, root+"/") { return false }
    c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 1<<20)
    if path == root && c.Request.Method == http.MethodGet {
        config, err := referencestorage.Load(s.dir)
        if err != nil { exportReply(c, 500, nil, "无法读取对象存储配置"); return true }
        exportReply(c, 200, config.Public(), "")
        return true
    }
    if c.Request.Method != http.MethodPost { exportReply(c, 405, nil, "不支持的方法"); return true }
    if !s.mu.TryLock() { exportReply(c, 409, nil, "正在上传或保存对象存储配置，请稍后重试"); return true }
    defer s.mu.Unlock()
    if path == root {
        var config referencestorage.Config
        if c.ShouldBindJSON(&config) != nil { exportReply(c, 400, nil, "配置格式无效"); return true }
        result, err := referencestorage.Save(s.dir, config)
        if err != nil { exportReply(c, 400, nil, "配置未保存，请检查 S3 端点、存储桶、公网地址和访问密钥"); return true }
        exportReply(c, 200, result, "")
        return true
    }
    config, err := referencestorage.Load(s.dir)
    if err != nil { exportReply(c, 500, nil, "无法读取对象存储配置"); return true }
    temp := filepath.Join(s.dir, "cache", "tmp")
    if err := os.MkdirAll(temp, 0700); err != nil { exportReply(c, 500, nil, "无法创建上传缓存"); return true }
    if path == root+"/test" {
        ctx, cancel := context.WithTimeout(c.Request.Context(), time.Minute)
        defer cancel()
        if err := referencestorage.Check(ctx, config, temp); err != nil { exportReply(c, 400, nil, "上传或公开读取测试失败，请检查对象存储权限和公网地址"); return true }
        exportReply(c, 200, nil, "")
        return true
    }
    if path == root+"/upload" {
        var request struct { IDs []string `json:"ids"` }
        if c.ShouldBindJSON(&request) != nil || len(request.IDs) == 0 || len(request.IDs) > 20 || mobileRuntime == nil {
            exportReply(c, 400, nil, "素材数量无效或工作区未就绪"); return true
        }
        staging, err := os.MkdirTemp(temp, "reference-mobile-")
        if err != nil { exportReply(c, 500, nil, "无法准备上传缓存"); return true }
        defer os.RemoveAll(staging)
        ctx, cancel := context.WithTimeout(c.Request.Context(), 10*time.Minute)
        defer cancel()
        links := make(map[string]string)
        for index, id := range request.IDs {
            if _, exists := links[id]; exists { continue }
            file := filepath.Join(staging, strconv.Itoa(index))
            if err := mobileRuntime.CopyOwnedResourceTo(id, file); err != nil {
                exportReply(c, 400, nil, fmt.Sprintf("参考素材 %d 无法读取，请检查所属工作区", index+1)); return true
            }
            link, err := referencestorage.Upload(ctx, config, file)
            if err != nil { exportReply(c, 400, nil, fmt.Sprintf("参考素材 %d 上传失败，请检查存储配置与网络", index+1)); return true }
            links[id] = link
        }
        exportReply(c, 200, links, "")
        return true
    }
    exportReply(c, 404, nil, "对象存储操作不存在")
    return true
}

