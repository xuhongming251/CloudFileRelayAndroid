# CloudFileRelay Android

原生 Android 客户端，支持在应用内登录并浏览 Civitai/Hugging Face、自动识别模型文件、解析设备登录态下的实际下载地址，并直接触发 GitHub Actions 完成网盘转存。

## 调用方式

应用直接调用以下仓库，不依赖转存网关：

- 仓库：`xuhongming251/upload_cloud_storage`
- 工作流：普通下载使用 `upload.yml`，百度网盘链接使用 `upload_linux.yml`
- 提交参数：`trace_id`、`url`、`local_file`、`channel`
- 状态：读取 workflow run、job steps 和名为 `result` 的 artifact

`channel` 与网盘的映射为：`0` 夸克、`1` 百度、`2` 移动云盘。

## Token 配置

为保持与 PC 端一致，本地构建时会优先读取：

1. Gradle 属性 `GITHUB_TOKEN`
2. 环境变量 `GITHUB_TOKEN`
3. 相邻 PC 项目 `../CloudFileRelay/src/main/index.js` 中的现有 Token

默认仓库配置可以通过 `GITHUB_OWNER`、`GITHUB_REPO`、`GITHUB_REF` Gradle 属性覆盖。Token 会进入 APK，因此应使用仅授权该仓库、仅含 Actions 必要权限的 fine-grained Token。

## 构建

```bash
export ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
./gradlew assembleRelease
```

也可以显式传入新 Token：

```bash
GITHUB_TOKEN=github_pat_xxx ./gradlew assembleRelease
```

## 应用签名

项目根目录的 `cloud-file-relay.keystore` 是本应用独立的发布证书。签名密码从不入库的
`keystore.properties` 读取。debug 与 release 使用相同的包名和证书，因此两种构建可以
通过 `adb install -r` 相互覆盖，并保留 GeckoView 登录态和本地任务数据。

本地签名配置包含以下字段：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`。
缺少该文件时，debug 会回退到 Android 默认调试证书，release 则输出未签名包。

## 已实现

- Civitai/Hugging Face 持久登录 WebView
- SPA 页面轮询识别与文件列表选择
- Civitai 官方模型 API 文件名映射
- Hugging Face `resolve` 文件识别和权重文件优先排序
- 设备端 Cookie 下载跳转解析，Cookie 不上传
- 直接触发 GitHub Actions，无需网关地址
- GitHub run、job 步骤进度和结果 artifact 查询
- 夸克、百度、移动云盘目标选择
- 本地任务持久化、状态筛选、进度条和资源打开
- Android 分享链接进入应用
