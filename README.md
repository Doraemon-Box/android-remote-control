# Android 远程控制 (公网版)

两台安卓手机通过公网互相远程控制，支持华为设备。

## 架构

```
手机A (控制端)                 信令服务器               手机B (被控端)
┌───────────────┐           ┌──────────┐           ┌───────────────┐
│ 触摸事件发送   │──WebSocket──│  房间管理  │──WebSocket──│ AccessService │
│ WebRTC 接收画面│◄──WebRTC P2P/TURN──────────────►│ MediaProjection│
└───────────────┘           └──────────┘           └───────────────┘
```

## 快速开始

### 1. 部署信令服务器

需要一台有公网IP的服务器 (阿里云/腾讯云均可):

```bash
cd server
npm install
node server.js
# 默认端口 8080，可通过 PORT 环境变量修改
```

生产环境建议用 nginx 反向代理 + SSL:
```nginx
server {
    listen 443 ssl;
    server_name your-domain.com;
    
    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;
    
    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

### 2. (可选) 部署 TURN 中继服务器

当两台手机无法P2P直连时需要 (严格NAT环境):

```bash
# Ubuntu 安装 coturn
sudo apt install coturn

# 编辑 /etc/turnserver.conf
listening-port=3478
tls-listening-port=5349
realm=your-domain.com
user=myuser:mypassword
fingerprint
lt-cred-mech

sudo systemctl start coturn
```

然后打开 App 首页右上角的 **服务器设置**，填写 TURN 地址、用户名和密码并保存，无需修改代码或重新编译。

### App 内服务器设置

- **信令服务器**：填写 `ws://主机:端口` 或 `wss://域名/路径`。保存新地址后自动重连，两台手机应配置同一信令服务。
- **TURN 服务器**：每行一个地址，例如 `turn:example.com:3478` 或 `turns:example.com:5349?transport=tcp`；填写服务端配置的用户名和密码。地址留空表示不使用 TURN 中继。
- 设置保存在 App 私有存储中，关闭 App、强制停止或重启手机后仍然保留；卸载或清除 App 数据会删除配置。
- TURN 设置用于下次建立的远程连接；远程会话中需先断开会话再修改配置。

### 录屏自动授权

在被控手机上先开启 LinkView 无障碍服务，再打开首页的 **录屏自动授权** 开关。开关默认关闭，设置保存在本机，重启 App 后保留。

开启后，收到远控请求时，App 会在 15 秒内尝试识别系统为 LinkView 显示的录屏授权弹窗，选择整个屏幕并点击开始。它没有永久录屏权限，每次仍由系统产生授权弹窗；无障碍服务只是辅助操作。不识别的系统/语言、受限制的控件或无障碍服务未运行时，需要手动确认。关闭开关、取消会话或完成授权后会立即停止辅助操作。

Android 14 及以上默认请求整个屏幕。启动 App 本身不再请求录屏，授权仅在收到远控请求时触发。请在实际被控设备上验证厂商系统兼容性。

### 3. 编译 Android APK

用 Android Studio 打开项目根目录:

```bash
# 或命令行编译
./gradlew assembleDebug
# APK 在 app/build/outputs/apk/debug/
```

### 4. 使用流程

#### 两台手机都安装 APK 后:

1. **两台手机** 打开 App，在首页右上角的 **服务器设置** 中填写同一信令服务器地址并保存，App 自动连接
2. 每台手机会获得一个 **6位连接码**
3. **控制端 (手机A)**: 输入 手机B 的连接码，点击 "请求控制"
4. **被控端 (手机B)**: 弹出确认对话框，点击 "允许"
5. 手机B 授权屏幕录制 (系统弹窗)
6. 手机A 进入远程控制界面，可以看到手机B的屏幕并操作

#### 支持的操作:
- 点击 (Tap)
- 长按 (Long Press)
- 滑动 (Swipe)
- 返回键 / Home键 / 最近任务

## 华为设备适配

华为 EMUI/HarmonyOS 有较严格的后台管控:

### 必须操作:
1. **关闭电池优化**: 设置 → 电池 → 启动管理 → 找到"远程控制" → 关闭自动管理，手动开启所有开关
2. **允许后台活动**: 设置 → 应用 → 远程控制 → 电池 → 允许后台活动
3. **开启无障碍服务** (被控端): 设置 → 辅助功能 → 已下载的服务 → 远程控制 → 开启
4. **允许显示在其他应用上方**: 设置 → 应用 → 远程控制 → 显示在其他应用上方

### 华为推送:
如果需要后台唤醒能力，可集成 HMS Push Kit 替代 FCM。
当前版本使用 WebSocket 长连接，App 在前台时无需推送。

## 技术说明

| 组件 | 技术 | 说明 |
|------|------|------|
| 信令服务器 | Node.js + ws | WebSocket 信令，房间管理 |
| 视频传输 | WebRTC | P2P直连，STUN/TURN穿透 |
| 屏幕采集 | MediaProjection API | Android 5.0+，需用户授权 |
| 输入注入 | AccessibilityService | 无需root，需手动开启 |
| 网络通信 | OkHttp WebSocket | 与信令服务器通信 |

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | 网络通信 |
| FOREGROUND_SERVICE | 后台屏幕采集 |
| SYSTEM_ALERT_WINDOW | 悬浮窗 |
| WAKE_LOCK | 保持屏幕/CPU活跃 |
| 无障碍服务 | 被控端执行触摸操作 |
| 屏幕录制 | 被控端采集屏幕画面 |

## 安全说明

- 连接码每次重新生成，一次性使用
- 被控端必须手动确认才允许远程控制
- 生产环境请使用 WSS (WebSocket over SSL)
- 建议自建 TURN 服务器，不要使用公共免费 TURN

## 项目结构

```
remote-control/
├── server/                          # 信令服务器
│   ├── package.json
│   └── server.js
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/remotecontrol/
│   │   ├── App.kt                   # Application 初始化
│   │   ├── network/
│   │   │   ├── SignalingClient.kt    # WebSocket 信令客户端
│   │   │   ├── SignalingListener.kt  # 信令事件接口
│   │   │   └── WebRTCManager.kt     # WebRTC 连接管理
│   │   ├── service/
│   │   │   ├── ScreenCaptureService.kt   # 屏幕采集前台服务
│   │   │   └── InputInjectionService.kt  # 无障碍输入注入
│   │   ├── ui/
│   │   │   ├── MainActivity.kt       # 主页面/配对
│   │   │   └── RemoteViewActivity.kt # 远程控制画面
│   │   └── util/
│   │       └── CoordinateMapper.kt   # 坐标映射
│   └── res/
│       ├── layout/
│       ├── values/
│       ├── drawable/
│       └── xml/
├── build.gradle
└── README.md
```
