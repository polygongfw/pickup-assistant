# 取件助手 · Android 版

一个基于菜鸟裹裹 MTOP H5 网关的取件查询与提醒工具，使用 Kotlin 编写，零第三方网络依赖。

> ⚠ **仅供个人学习 / 研究**。本项目通过逆向菜鸟裹裹的 MTOP H5 网关协议获取数据，
> 与菜鸟裹裹（Cainiao）、淘宝（Taobao）、天猫（Tmall）及其权利人无任何关联、未获其授权或背书。
> 请自行遵守目标服务的使用条款与当地法律。**请勿公开发布上架。**

## 功能

### 待取件（首页）
- 登录后自动拉取你淘宝账号下所有未签收的包裹
- 按到站 / 运输中 / 已签收三组分类，支持折叠与筛选
- 每件到站包裹直接在卡片上显示取件码
- 滞留天数标记（≥1 天黄色，≥3 天红色）
- 驿站营业时间 + 打烊倒计时 + 拨打驿站电话
- 一键复制全部取件码

### 取件码出示页
- 超大字号取件码，自动缩放适配不同长度
- 自动把屏幕亮度拉到最满 + 防息屏
- 整页深色设计，方便给驿站工作人员看

### 查快递
- 免登录通道，输入运单号即可查询
- 时间轴展示轨迹
- 快递公司客服电话可点击拨打

### 我的
- 多账号管理（支持同时登录多个淘宝账号）
- 登录态验证与刷新
- Cookie 文本粘贴导入（备用登录方式）

### 取件提醒
- 每天 11:30 和 19:30 自动检查
- 有新包裹到站或滞留件时发送本地通知
- Android 13+ 运行时申请通知权限

## 登录方式

支持两种登录方式，均不经过任何第三方服务器：

1. **淘宝扫码登录（推荐）**：App 本地渲染二维码，用手机淘宝扫码确认即可
2. **账号密码兜底**：内嵌 WebView 打开淘宝官方登录页，登录成功后自动抓取 Cookie

## 技术实现

- **MTOP H5 网关客户端**：纯 JDK `HttpURLConnection`，零第三方网络库
- **签名算法**：`md5(token & t & appKey & data)`，覆盖 `TOKEN_EMPTY` / `TOKEN_EXPIRED` / `TOKEN_EXOIRED` 等返回码
- **多账号**：每个账号一个独立的 Cookie 槽位，并发拉取合并去重
- **定时通知**：`WorkManager` 周期任务，网络约束
- **二维码渲染**：本地 `assets/qrcode.js`（源自 [davidshimjs/qrcodejs](https://github.com/davidshimjs/qrcodejs)，MIT License），数据不出网
- **UI**：原生 View（无 Compose），ViewBinding，单 Activity 多 Fragment

## 构建

### 环境要求

| 组件 | 版本 |
|------|------|
| JDK | 17+ |
| Android SDK | compileSdk 34, build-tools 34.0.0 |
| Gradle | 8.7 |

### 构建命令

```bash
export MSYS_NO_PATHCONV=1
bash gradle.sh :app:assembleDebug    # 调试包
bash gradle.sh :app:assembleRelease  # 正式包（需自行配置签名）
```

`gradle.sh` 按以下优先级解析构建环境：
1. 环境变量 `JAVA_HOME` / `ANDROID_HOME` / `GRADLE_LAUNCHER`
2. 同目录下的 `gradle.local.sh`（已 gitignore，格式见 `gradle.local.sh.example`）
3. 自动探测（PATH 上的 `java` / `local.properties` 中的 `sdk.dir`）

### 签名

如需构建 release 签名包，在仓库根目录创建 `keystore.properties`（已 gitignore）：

```properties
storeFile=你的密钥文件路径
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

文件不存在时 release 构建不带签名（仍可正常执行 `assembleDebug`）。

### 测试

```bash
bash gradle.sh :app:testDebugUnitTest
```

包含 3 组单元测试（`AccountMergeTest` / `MtopLiveTest` / `QrLoginTest`），
其中联网用例会真实调用淘宝网关验证签名与扫码状态机，网络不可用时自动跳过。

## 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | 请求淘宝网关 |
| `ACCESS_NETWORK_STATE` | 定时任务的网络约束 |
| `POST_NOTIFICATIONS` | 取件提醒（Android 13+ 运行时申请，可拒绝） |
| `WAKE_LOCK` | WorkManager 定时唤醒 |

没有相机、没有存储、没有定位、没有通讯录。

## 数据与隐私

- 所有数据只在手机和淘宝网关之间传输，不经过任何第三方服务器
- 登录凭证存在 App 私有目录（`SharedPreferences`），非 root 设备风险可控
- 二维码本地渲染，不调用在线出图 API

## 已知限制

1. 登录态会不定期失效（服务端策略），失效时首页会提示重新登录
2. 取件码如果是 `NEEDSMS`（需短信验证），App 只能显示"需短信验证"
3. WorkManager 定时任务在 Doze 模式下可能被推迟
4. 免登录查询快递走 `pid` 通道，不触发滑块，但不保证永久可用

## 目录结构

```
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/qrcode.js        二维码渲染（qrcodejs）
│       │   ├── java/com/coldbrew/cainiao/
│       │   │   ├── App.kt
│       │   │   ├── data/
│       │   │   │   ├── Api.kt          接口常量与签名规则
│       │   │   │   ├── Mtop.kt         MTOP H5 网关客户端
│       │   │   │   ├── QrLogin.kt      淘宝扫码登录客户端
│       │   │   │   ├── Cookies.kt      Set-Cookie 解析
│       │   │   │   ├── CookieStore.kt  会话能力抽象
│       │   │   │   ├── SessionStore.kt Cookie 持久化（按账号分槽）
│       │   │   │   ├── AccountStore.kt 多账号注册表
│       │   │   │   ├── Models.kt       包裹模型
│       │   │   │   ├── Repo.kt         业务门面
│       │   │   │   └── Settings.kt     设置与通知快照
│       │   │   ├── ui/
│       │   │   │   ├── MainActivity.kt      底部导航
│       │   │   │   ├── PickupFragment.kt    待取件首页
│       │   │   │   ├── CodeActivity.kt      取件码出示页
│       │   │   │   ├── SearchFragment.kt    查快递
│       │   │   │   ├── MineFragment.kt      我的 / 账号管理
│       │   │   │   ├── QrLoginActivity.kt   扫码登录
│       │   │   │   ├── LoginActivity.kt     账号密码兜底登录
│       │   │   │   └── ...
│       │   │   └── work/
│       │   │       ├── NotifyWorker.kt       定时通知
│       │   │       ├── NotifyScheduler.kt    定时任务编排
│       │   │       └── NotificationChannels.kt
│       │   └── res/ ...
│       └── test/ ...                     单元测试
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle.sh
├── gradle.local.sh.example
├── LICENSE                               MIT + 第三方声明
└── .gitignore
```

## License

[MIT](LICENSE)
