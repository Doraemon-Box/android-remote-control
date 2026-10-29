const http = require('http');
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');

const PORT = process.env.PORT || 8080;
const APK_PATH = path.join(__dirname, '..', 'dl', 'my.apk');

// HTTP server: serves files from dl/ and dl/tools/
const httpServer = http.createServer((req, res) => {
  const dlDir = path.join(__dirname, '..', 'dl');
  const toolsDir = '/root/my/tools';
  const url = decodeURIComponent(req.url);

  // /tools/ — list all downloadable files
  if (url === '/tools' || url === '/tools/') {
    if (!fs.existsSync(toolsDir)) {
      res.writeHead(404);
      res.end('Tools directory not found');
      return;
    }
    const files = fs.readdirSync(toolsDir);
    const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Tools</title><style>body{font-family:sans-serif;max-width:600px;margin:40px auto;padding:0 16px}
a{display:block;padding:14px 16px;margin:8px 0;background:#f5f5f5;border-radius:8px;text-decoration:none;color:#1976D2;font-size:16px}
a:active{background:#e0e0e0}.size{color:#999;font-size:13px;float:right}</style></head><body>
<h2>Tools Download</h2>${files.map(f => {
  const stat = fs.statSync(path.join(toolsDir, f));
  const sizeMB = (stat.size / 1048576).toFixed(1);
  return `<a href="/tools/${f}">${f}<span class="size">${sizeMB} MB</span></a>`;
}).join('')}</body></html>`;
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(html);
    return;
  }

  // /tools/filename — download file
  if (url.startsWith('/tools/')) {
    const filename = path.basename(url.slice(7));
    const file = path.join(toolsDir, filename);
    if (fs.existsSync(file)) {
      const stat = fs.statSync(file);
      const ext = path.extname(filename).toLowerCase();
      const contentType = ext === '.apk' ? 'application/vnd.android.package-archive' : 'application/octet-stream';
      res.writeHead(200, {
        'Content-Type': contentType,
        'Content-Length': stat.size,
        'Content-Disposition': `attachment; filename="${filename}"`,
      });
      fs.createReadStream(file).pipe(res);
    } else {
      res.writeHead(404);
      res.end('File not found');
    }
    return;
  }

  // Legacy direct APK downloads
  if (url === '/my.apk' || url === '/rustdesk.apk') {
    const filename = url.slice(1);
    const file = path.join(dlDir, filename);
    if (fs.existsSync(file)) {
      const stat = fs.statSync(file);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': `attachment; filename="${filename}"`,
      });
      fs.createReadStream(file).pipe(res);
    } else {
      res.writeHead(404);
      res.end('APK not found');
    }
    return;
  }

  res.writeHead(200);
  res.end('Signaling server OK');
});

const wss = new WebSocket.Server({ server: httpServer });

// Device registry: deviceId -> { ws, deviceName, online }
const devices = new Map();
// Room registry: roomId -> { controller: deviceId, controlled: deviceId }
const rooms = new Map();

function sendTo(deviceId, message) {
  const device = devices.get(deviceId);
  if (device && device.ws.readyState === WebSocket.OPEN) {
    device.ws.send(JSON.stringify(message));
  }
}

// Broadcast online device list to all connected devices
function broadcastDeviceList() {
  const list = [];
  for (const [id, dev] of devices.entries()) {
    if (dev.ws.readyState === WebSocket.OPEN) {
      list.push({ deviceId: id, deviceName: dev.deviceName });
    }
  }
  const msg = JSON.stringify({ type: 'device_list', devices: list });
  for (const [, dev] of devices.entries()) {
    if (dev.ws.readyState === WebSocket.OPEN) {
      dev.ws.send(msg);
    }
  }
}

function cleanupDevice(deviceId, socket) {
  // A late close/error from a replaced socket must not remove its replacement.
  if (devices.get(deviceId)?.ws !== socket) return;
  // Clean up rooms
  for (const [roomId, room] of rooms.entries()) {
    if (room.controller === deviceId || room.controlled === deviceId) {
      const otherId = room.controller === deviceId ? room.controlled : room.controller;
      sendTo(otherId, { type: 'room_closed', roomId });
      rooms.delete(roomId);
    }
  }
  devices.delete(deviceId);
  broadcastDeviceList();
}

wss.on('connection', (ws) => {
  let currentDeviceId = null;
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data) => {
    let msg;
    try {
      msg = JSON.parse(data);
    } catch (e) {
      ws.send(JSON.stringify({ type: 'error', message: 'Invalid JSON' }));
      return;
    }

    if (!msg || typeof msg !== 'object') return;
    if (msg.type !== 'register' && devices.get(currentDeviceId)?.ws !== ws) {
      ws.send(JSON.stringify({ type: 'error', message: 'Device not registered' }));
      return;
    }
    switch (msg.type) {
      case 'register': {
        // Repeated register on one socket is idempotent, even for legacy clients.
        const deviceId = currentDeviceId ||
          (typeof msg.deviceId === 'string' && msg.deviceId.length <= 128 && msg.deviceId
            ? msg.deviceId : uuidv4());
        const previous = devices.get(deviceId);
        if (previous && previous.ws !== ws) {
          cleanupDevice(deviceId, previous.ws);
          previous.ws.terminate();
        }
        currentDeviceId = deviceId;
        devices.set(deviceId, {
          ws,
          deviceName: msg.deviceName || 'Unknown',
        });
        ws.send(JSON.stringify({ type: 'registered', deviceId }));
        console.log(`Device registered: ${deviceId} (${msg.deviceName})`);
        broadcastDeviceList();
        break;
      }

      // Direct connect by deviceId (no code needed)
      case 'connect_to': {
        const targetDeviceId = msg.targetDeviceId;
        if (!targetDeviceId || targetDeviceId === currentDeviceId) {
          ws.send(JSON.stringify({ type: 'error', message: 'Invalid target' }));
          return;
        }
        const targetDevice = devices.get(targetDeviceId);
        if (!targetDevice || targetDevice.ws.readyState !== WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'error', message: 'Target device offline' }));
          return;
        }
        const roomId = uuidv4();
        rooms.set(roomId, {
          controller: currentDeviceId,
          controlled: targetDeviceId,
        });
        // Auto-accept: notify both sides directly
        sendTo(currentDeviceId, {
          type: 'control_accepted',
          roomId,
        });
        sendTo(targetDeviceId, {
          type: 'room_joined',
          roomId,
          role: 'controlled',
        });
        console.log(`Room created: ${roomId} (${currentDeviceId} -> ${targetDeviceId})`);
        break;
      }

      // WebRTC signaling
      case 'offer':
      case 'answer':
      case 'ice_candidate': {
        const room = rooms.get(msg.roomId);
        if (!room || (room.controller !== currentDeviceId && room.controlled !== currentDeviceId)) {
          ws.send(JSON.stringify({ type: 'error', message: 'Room not found' }));
          return;
        }
        const targetId = room.controller === currentDeviceId
          ? room.controlled
          : room.controller;
        sendTo(targetId, {
          type: msg.type,
          roomId: msg.roomId,
          data: msg.data,
        });
        break;
      }

      // Touch/input events
      case 'input_event': {
        const room2 = rooms.get(msg.roomId);
        if (!room2 || room2.controller !== currentDeviceId) {
          ws.send(JSON.stringify({ type: 'error', message: 'Not authorized' }));
          return;
        }
        sendTo(room2.controlled, {
          type: 'input_event',
          roomId: msg.roomId,
          event: msg.event,
        });
        break;
      }

      case 'disconnect_room': {
        const room3 = rooms.get(msg.roomId);
        if (room3 && (room3.controller === currentDeviceId || room3.controlled === currentDeviceId)) {
          const otherId = room3.controller === currentDeviceId
            ? room3.controlled
            : room3.controller;
          sendTo(otherId, { type: 'room_closed', roomId: msg.roomId });
          rooms.delete(msg.roomId);
          console.log(`Room closed: ${msg.roomId}`);
        }
        break;
      }

      case 'ping': {
        ws.send(JSON.stringify({ type: 'pong' }));
        break;
      }

      default:
        ws.send(JSON.stringify({ type: 'error', message: `Unknown type: ${msg.type}` }));
    }
  });

  ws.on('close', () => {
    if (currentDeviceId) {
      console.log(`Device disconnected: ${currentDeviceId}`);
      cleanupDevice(currentDeviceId, ws);
    }
  });

  ws.on('error', (err) => {
    console.error(`WebSocket error: ${err.message}`);
    if (currentDeviceId) {
      cleanupDevice(currentDeviceId, ws);
    }
  });
});

// Remove half-open sockets left by Wi-Fi/mobile switching or a killed process.
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      for (const [id, device] of devices) {
        if (device.ws === ws) cleanupDevice(id, ws);
      }
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 15000);
heartbeat.unref();
wss.on('close', () => clearInterval(heartbeat));

httpServer.listen(PORT, () => {
  console.log(`Server running on http://0.0.0.0:${httpServer.address().port}`);
  console.log(`APK download: http://0.0.0.0:${PORT}/my.apk`);
  console.log(`WebSocket signaling: ws://0.0.0.0:${PORT}`);
});
