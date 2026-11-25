// SPDX-License-Identifier: GPL-2.0-or-later
// Local verification receiver only. It is never bundled into the Android app.
const fs = require('fs');
const http = require('http');
const path = require('path');
const RtmpServer = require('node-media-server/src/node_rtmp_server');
const HttpServer = require('node-media-server/src/node_http_server');
const context = require('node-media-server/src/node_core_ctx');
const output = path.resolve(process.argv[2] || 'received.flv');
fs.mkdirSync(path.dirname(output), { recursive: true });
const config = { logType: 2, rtmp: { port: 1935, chunk_size: 60000, gop_cache: true, ping: 30, ping_timeout: 60 },
  http: { port: 18080, api: false, mediaroot: path.dirname(output), allow_origin: '' } };
const rtmp = new RtmpServer(config);
const web = new HttpServer(config);
let request, recording, bytes = 0, finished = false;
function finish() {
  if (finished) return;
  finished = true;
  if (request) request.destroy();
  const done = () => { console.log(JSON.stringify({ event: 'RECEIVER_DONE', bytes, output })); process.exit(bytes > 0 ? 0 : 1); };
  if (recording) recording.end(done); else done();
}
context.nodeEvent.on('prePublish', (id, stream) => { if (stream !== '/live/android') context.sessions.get(id).reject(); });
context.nodeEvent.on('postPublish', (id, stream) => {
  if (stream !== '/live/android') return;
  console.log('PUBLISH_ACCEPTED');
  recording = fs.createWriteStream(output);
  request = http.get('http://127.0.0.1:18080/live/android.flv', response => {
    response.on('data', data => { bytes += data.length; recording.write(data); });
    response.on('end', finish);
  });
  request.on('error', error => { if (!finished) console.error(error.message); });
});
context.nodeEvent.on('donePublish', () => setTimeout(finish, 500));
rtmp.tcpServer.on('error', error => { console.error(error); process.exit(1); });
web.httpServer.on('error', error => { console.error(error); process.exit(1); });
rtmp.tcpServer.listen(1935, '127.0.0.1', () => console.log('RTMP_LISTENING 127.0.0.1:1935'));
web.httpServer.listen(18080, '127.0.0.1');
setTimeout(finish, 90000);
