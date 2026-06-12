# ZJU 校园网自动连接 (Android)

浙大深澜 srun_bx1 认证的安卓客户端。**检测到 ZJUWLAN WiFi 即自动登录**,零人工操作。

> PC 版对应脚本:`/home/henry/internet.pyw`  
> 加密算法 100% 移植自该脚本,并在单元测试里与 Python 输出做了等价验证。

## 功能

- 🔄 **全自动**:连上 ZJUWLAN 立刻触发登录,无需开 App
- 🔐 **加密存储**:账号密码用 `EncryptedSharedPreferences` (AES-256-GCM + SIV) 保存
- 🔁 **开机自启**:`BootReceiver` 监听开机广播,自动恢复监控
- 📊 **状态卡片**:实时显示已连/未连/累计成功次数
- 🔒 **无代理**:强制 `Proxy.NO_PROXY`,绕开系统代理干扰(对应 PC 脚本的 `trust_env=False`)
- 🌐 **免 SSL 验证**:`net.zju.edu.cn` 的证书链对 Android 不可信,跳过验证(对应 PC 脚本的 `verify=False`)

## 安装

### 方式 A:从 GitHub Actions 下载(推荐)

1. 推送代码到 GitHub
2. 进入 `Actions` tab,选择 `Build APK` workflow
3. 完成后从 `Artifacts` 下载 `ZJU-AutoConnect-debug`
4. 手机打开 `设置 → 安全 → 安装未知应用`,授权文件管理器或浏览器
5. 安装 APK,首次启动授予通知 + 位置权限

### 方式 B:本地构建

```bash
# 需 JDK 17
./gradlew :app:assembleDebug
# APK 路径: app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 打开 App,填入学号和校园网密码,点 **保存**
2. 点 **启动服务** → 授予通知权限(必须,否则前台服务无法运行)
3. 服务启动后,连接到 `ZJUWLAN` 即可自动登录
4. 拔掉 WiFi/关机,下次开机服务会自动恢复(需开启"开机自启",部分 ROM 需手动在系统设置中允许)

### 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | 走 HTTPS 调用 `net.zju.edu.cn` 登录接口 |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 监听 WiFi 连接 |
| `FOREGROUND_SERVICE` | 后台保活(显示常驻通知) |
| `POST_NOTIFICATIONS` | Android 13+ 通知运行时权限 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `ACCESS_FINE_LOCATION` | Android 13+ 读取 SSID 必须 |

### 国产 ROM 额外设置

MIUI / EMUI / ColorOS / OriginOS / OneUI 等深度定制系统会限制应用自启,**必须**额外开启:

- **小米 (MIUI/HyperOS)**:设置 → 应用 → ZJU 校园网 → 自启动 ✅,省电策略 → 无限制
- **华为 (EMUI/HarmonyOS)**:设置 → 应用 → ZJU 校园网 → 自启动 ✅,电池优化 → 不允许
- **OPPO (ColorOS)**:设置 → 电池 → 更多设置 → 关闭"睡眠待机优化",应用 → ZJU 校园网 → 自启动 ✅
- **vivo (OriginOS)**:设置 → 电池 → 后台高耗电 → 允许 ZJU 校园网
- **一加 (OxygenOS)**:设置 → 电池 → 电池优化 → ZJU 校园网 → 不优化
- **三星 (OneUI)**:设置 → 应用程序 → ZJU 校园网 → 电池 → 不受监控

## 项目结构

```
ZJU-AutoConnect/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/cn/cyhkbl/zjuautoconnect/
│   │   │   ├── SrunCrypto.kt          # 加密算法 (XEncode + Base64 + HMAC-MD5 + SHA1)
│   │   │   ├── SrunLogin.kt           # 登录流程封装 (init → challenge → login)
│   │   │   ├── NetworkMonitorService.kt  # 前台服务 + WiFi 监听
│   │   │   ├── BootReceiver.kt        # 开机广播
│   │   │   ├── PrefsManager.kt        # EncryptedSharedPreferences
│   │   │   └── MainActivity.kt        # UI
│   │   └── res/                       # 布局、主题、图标
│   ├── src/test/                      # 单元测试 (与 Python 等价)
│   └── build.gradle.kts
├── .github/workflows/build-apk.yml    # GitHub Actions 云构建
├── settings.gradle.kts
└── build.gradle.kts
```

## 加密算法

完整对应 PC 脚本 `internet.pyw`:

| Python | Kotlin |
|---|---|
| `force(msg)` | `SrunCrypto.force` (UTF-8 字节化) |
| `ordat(msg, i)` | `SrunCrypto.ordat` |
| `sencode(msg, key)` | `SrunCrypto.sencode` |
| `lencode(msg, key)` | `SrunCrypto.lencode` |
| `get_xencode(msg, key)` | `SrunCrypto.getXencode` |
| `get_base64(s)` | `SrunCrypto.getBase64` (深澜自定义字母表) |
| `hmac.new(t, p, md5).hexdigest()` | `SrunCrypto.getMd5` (HmacMD5) |
| `hashlib.sha1(v).hexdigest()` | `SrunCrypto.getSha1` |

所有算术用 Kotlin `Int` 模拟 Python 的任意精度整数 + `& 0xFFFFFFFF` 截断 (`Int` 算术自动 mod 2³²)。位运算用 `shr` (有符号算术右移) 与 Python `>>` 一致。

## 单元测试

```bash
./gradlew :app:testDebugUnitTest
```

测试覆盖与 Python `internet.pyw` 的等价性,固定 expected 值由 Python 计算后硬编码:

- `getXencode("a", "k")` = `10d188dc61522d85`
- `getXencode("hello world", "foobar")` = `d68edf8f163d8d8f08736f9d111eb18c`
- `getBase64("A")` = `"++=="`
- `getMd5("password", "token123")` = `ebf4b9558b17e165c641ff3678d4ddb2`
- `getSha1("test")` = `a94a8fe5ccb19ba61c4c0873d391e987982fbbd3`
- `buildInfo` 端到端 + `buildChksum` 端到端

## 安全说明

- 账号密码仅本地保存,使用 `MasterKey.KeyScheme.AES256_GCM` 加密
- 不收集任何信息,无任何网络分析/统计 SDK
- 所有网络请求直连 `net.zju.edu.cn`,强制无代理(防泄露)
- `backup_rules.xml` 排除 prefs 文件,防云端备份泄露密码

## License

MIT
