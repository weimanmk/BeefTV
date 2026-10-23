# Android 手机分支

此分支为实验性个人自用移植，保留上游许可与署名，不是官方 Android 发布。

## 打包和安装

GitHub Actions → **Android APK** → 最新成功运行 → **BeefTV-Mobile-APK**，解压后安装 `app-debug.apk`。
也可以在 `android-mobile` 分支手动运行工作流。产物是用于侧载测试的 debug 签名 APK，不是应用商店正式发行版。
未配置持久发布签名，不同 runner 构建可能需要卸载旧版才能安装；卸载会删除手机工作区，先导出项目。

Android 8.0 以上，支持 arm64-v8a 真机和 x86_64 模拟器。需要近期版本的 Android System WebView；推荐 Android 10 以上。
首次打开先完成工作区初始化，再在模型渠道设置中填写自己的模型服务。仓库和 APK 均不包含用户密钥。

## 架构与数据

React 生产资源和官方模型渠道协议插件嵌入 Go 共享库，通过 JNI 启动 SQLite 和原有工作区 API。协议插件按内容指纹释放到应用私有目录。系统 WebView 连接手机回环地址；不要求运行电脑或外部 BeefTV 服务器。
地址固定为 `127.0.0.1:18789`，以保持浏览器存储的稳定来源。端口冲突会显示启动错误，不会接入其他服务。
每个进程生成随机会话，所有 API、媒体和界面请求必须携带 HttpOnly / SameSite=Strict Cookie；拒绝非本机 Host 和跨域 Origin。
明文 HTTP 仅放行回环地址，外部模型服务须使用 HTTPS。文件选择和保存使用 Android 系统文件对话框，不申请广泛存储权限。
数据库、渠道设置、素材在应用私有目录。未启用系统云备份；卸载会清除数据。系统结束进程时任务会中断，应用不提供后台常驻服务。

## 首版边界

- 复用原有窄屏导航和画布触控缩放；接入系统文件选择、原文件下载、返回键、视频全屏及屏幕安全区域。
- 原桌面 Node 助手宿主、外部插件可执行程序、原生 FFmpeg/ffprobe、深度采集和本机桌面集成尚未移植。这些功能不能按桌面版验收。
- 精简资源构建不打包重型 WASM / 3D 资源；视频转码、时间线渲染和相关工具不可用。
- Blob 导出采用内存传递，上限 32MB；更大文件需下载素材原文件。
- 麦克风录音和相机直拍尚未接入，素材通过系统文件选择器导入。
- 模型生成需要用户可用的渠道、网络和额度；构建测试不会调用付费模型。

## 本地构建

在 Linux 安装项目要求的 Go、Bun、JDK 17、Gradle 8.11.1、Android SDK 35 和 NDK 28.2.13676358 后：

```sh
export ANDROID_HOME=/path/to/android-sdk
bash scripts/build-android.sh
```

流程先构建前端并运行移动 HTTP 边界测试，再为两种架构编译 Go 共享库，最后生成 APK。
native 库使用 16KB ELF 对齐。`android/app/src/main/jniLibs`、Gradle 缓存和 APK 均不提交源码。
