// Node 16-compatible integration regression test against an isolated local server.
const assert = require('assert');
const { spawn } = require('child_process');
const WebSocket = require('ws');

async function main() {
  const server = spawn(process.execPath, ['server.js'], {
    cwd: __dirname, env: { ...process.env, PORT: '0' }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  const sockets = [];
  try {
    const port = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Server startup timed out')), 5000);
      server.once('exit', code => { clearTimeout(timer); reject(new Error(`Server exited ${code}`)); });
      server.stdout.on('data', chunk => {
        const match = chunk.toString().match(/Server running on http:\/\/[^:]+:(\d+)/);
        if (match) { clearTimeout(timer); resolve(match[1]); }
      });
    });
    async function connect() {
      const ws = new WebSocket(`ws://127.0.0.1:${port}`);
      sockets.push(ws);
      const messages = [];
      ws.on('message', data => messages.push(JSON.parse(data)));
      await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
      return { ws, messages };
    }
    async function receive(client, type) {
      const deadline = Date.now() + 3000;
      while (Date.now() < deadline) {
        const index = client.messages.findIndex(m => m.type === type);
        if (index >= 0) return client.messages.splice(index, 1)[0];
        await new Promise(resolve => setTimeout(resolve, 10));
      }
      throw new Error(`Timed out waiting for ${type}`);
    }
    async function register(client, id) {
      client.ws.send(JSON.stringify({ type: 'register', deviceId: id, deviceName: 'Same model' }));
      return (await receive(client, 'registered')).deviceId;
    }
    const first = await connect();
    assert.strictEqual(await register(first, 'phone-a'), 'phone-a');
    const replacement = await connect();
    assert.strictEqual(await register(replacement, 'phone-a'), 'phone-a');
    assert.strictEqual((await receive(replacement, 'device_list')).devices.length, 1);
    // Wait for replacement termination/late close before proving it remains usable.
    if (first.ws.readyState !== WebSocket.CLOSED) {
      await new Promise(resolve => first.ws.once('close', resolve));
    }
    const other = await connect();
    assert.strictEqual(await register(other, 'phone-b'), 'phone-b');
    const list = await receive(other, 'device_list');
    assert.deepStrictEqual(list.devices.map(d => d.deviceId).sort(), ['phone-a', 'phone-b']);
    // Same model is not treated as the same device. Replacement can still receive a room.
    other.ws.send(JSON.stringify({ type: 'connect_to', targetDeviceId: 'phone-a' }));
    const joined = await receive(replacement, 'room_joined');
    assert.ok(joined.roomId);
    const legacy = await connect();
    const legacyId = await register(legacy);
    assert.strictEqual(await register(legacy), legacyId);
    const lists = legacy.messages.filter(m => m.type === 'device_list');
    assert.strictEqual(lists[lists.length - 1].devices.length, 3);
    console.log('PASS: replacement, late close, distinct same-model devices, room delivery, legacy re-register');
  } finally {
    sockets.forEach(ws => ws.terminate());
    server.kill();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
