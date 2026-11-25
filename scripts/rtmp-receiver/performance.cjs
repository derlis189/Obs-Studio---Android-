// SPDX-License-Identifier: GPL-2.0-or-later
// Loopback-only verification receiver; never packaged into the app.
const fs = require('fs');
const http = require('http');
const path = require('path');
const RtmpServer = require('node-media-server/src/node_rtmp_server');
const NodeFlvSession = require('node-media-server/src/node_flv_session');
const {EventEmitter} = require('events');
const context = require('node-media-server/src/node_core_ctx');
const destination = path.resolve(process.argv[2]);
fs.mkdirSync(destination, {recursive: true});
const config = {logType: 0, rtmp: {port: 1935, chunk_size: 60000, gop_cache: true, ping: 30, ping_timeout: 60},
  http: {port: 18080, api: false, mediaroot: destination, allow_origin: ''}};
const rtmp = new RtmpServer(config);
const sessions = new Map();
let sequence = 0;
context.nodeEvent.on('prePublish' , (id, stream) => {
  if (!['/live/perf_a', '/live/perf_b', '/live/perf_c', '/live/perf_d', '/live/perf_e', '/live/perf_f'].includes(stream)) context.sessions.get(id).reject();
});
context.nodeEvent.on('postPublish', (id, stream) => {
  const name = stream.split('/').pop() + '-' + (++sequence) + '.flv';
  const file = fs.createWriteStream(path.join(destination, name));
  const item = {stream, file, bytes: 0};
  sessions.set(id, item);
  // A local FLV subscriber consumes the server's actual parsed RTMP packets.
  // It writes directly to disk, avoiding HTTP/socket cork behaviour in old NMS.
  const socket = new EventEmitter();
  socket.remoteAddress = '127.0.0.1'; socket.cork = socket.uncork = () => {};
  const request = new EventEmitter();
  Object.assign(request, {method: 'GET', url: stream + '.flv', socket, nmsConnectionType: 'http'});
  const response = {socket,
    write(data, encoding, callback) { item.bytes += data.length; return file.write(data, callback); },
    end() { file.end(); }};
  item.player = new NodeFlvSession(config, request, response);
  item.player.run();
  console.log(JSON.stringify({event: 'publish', stream, file: name}));
});
context.nodeEvent.on('donePublish', id => {
  const item = sessions.get(id);
  if (!item) return;
  if (item.player) item.player.stop();
  item.file.end();
  console.log(JSON.stringify({event: 'done', stream: item.stream, bytes: item.bytes}));
  sessions.delete(id);
});
const control = http.createServer((request, response) => {
  if (request.method === 'POST' && request.url === '/drop') {
    for (const id of [...sessions.keys()]) context.sessions.get(id)?.stop();
    response.end('dropped');
  } else if (request.url === '/state') {
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify([...sessions.values()].map(item => ({stream: item.stream, bytes: item.bytes}))));
  } else {response.writeHead(404); response.end();}
});
for (const server of [rtmp.tcpServer, control]) server.on('error', error => {console.error(error.message); process.exit(1);});
rtmp.tcpServer.listen(1935, '127.0.0.1', () => console.log('RTMP_READY'));
control.listen(18081, '127.0.0.1');
