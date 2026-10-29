# 真机远控黑屏排查

设备：被控 OPPO PKW120（3K658X002MX00000），控制 OnePlus PKR110（2b053a01）。

## 复现证据

2026-10-28 11:10，两端安装增加 ICE 诊断的 debug APK 后，在原有网络复现。

- 信令正常交换会话、SDP 和 ICE 候选；收到的候选 `accepted=true`。
- 被控端录屏授权 `granted=true`，采集启动为 540×1202，20fps。
- 被控端仅生成 UDP host 候选；控制端生成 host、srflx 候选。
- 两端均未生成 relay 候选。
- 两端 ICE 从 CHECKING 进入 FAILED，约 15 秒。
- 被控端 Google STUN 和默认公共 TURN 的 UDP 请求均超时。
- 两端默认 TURN `openrelay.metered.ca:80/443?transport=udp` 返回 701，`TURN allocate request timed out`。
- 额外用手机自身 `toybox nc -z -w 5` 检查该主机 TCP 80、443，两端四次检查均超时。这只验证 TCP 建连，不验证 TURN 鉴权。

## 结论与边界

本次黑屏发生在媒体连接建立之前：当前网络没有形成可用直连路径，同时配置的公共 TURN 未提供可用中继地址。没有证据表明本次是解码、渲染或录屏授权失败。

无法仅凭超时区分远端服务不可用、线路过滤或其他网络问题；不能据此认定所有 UDP 均被屏蔽。

下一步需在两端设置中配置可达且凭据有效的 TURN，或先在同一局域网验证直连。跨网络稳定使用建议部署自己的 coturn，开放监听端口及中继 UDP 端口范围；可按实际服务配置添加 TCP/TLS 备用地址。信令服务 8080 端口不承担 TURN 中继。

## 本次代码改动

WebRTCManager 增加本地/远端候选类型、候选接收结果、候选收集状态和 ICE 服务错误日志，不记录 TURN 密码。未修改连接策略或覆盖手机已有配置。

诊断 APK 已在两端安装；assembleDebug、lintDebug 成功。原始会话日志保留在开发电脑 `/private/tmp/ice-3K658X002MX00000.log`、`/private/tmp/ice-2b053a01.log`（临时目录可能被系统清理）。
