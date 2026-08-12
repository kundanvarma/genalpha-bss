/*
 * Mock Whisper — a Whisper-API-shaped speech-to-text provider for proving the
 * bring-your-own STT seam without OpenAI credentials or a GPU. Speaks the one
 * endpoint the SpeechController proxies to:
 *
 *   POST /v1/audio/transcriptions  (multipart: file, model)  -> { text }
 *
 * Returns a canned transcript (the demo "hears" a catalog request); a real
 * Whisper API / whisper.cpp server needs only the tenant's speech-url changed.
 * Requires the Bearer token when MOCK_TOKEN is set — the seam's auth proven.
 */
'use strict';

const http = require('http');

const PORT = process.env.PORT || 8080;
const TOKEN = process.env.MOCK_TOKEN || '';
const TRANSCRIPT = process.env.TRANSCRIPT
  || 'create a ten gigabyte mobile plan called Voice Starter at nineteen euros';

const server = http.createServer((req, res) => {
  const json = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };

  if (req.method === 'POST' && req.url === '/v1/audio/transcriptions') {
    if (TOKEN && req.headers.authorization !== `Bearer ${TOKEN}`) {
      req.resume();
      return json(401, { error: 'invalid token' });
    }
    let size = 0;
    req.on('data', (c) => { size += c.length; });
    req.on('end', () => {
      if (size === 0) return json(400, { error: 'no audio' });
      console.log(`[mock-whisper] transcribed ${size} byte(s)`);
      return json(200, { text: TRANSCRIPT });
    });
    return;
  }

  if (req.url === '/health' || req.url === '/') return json(200, { ok: true, stt: 'mock-whisper' });
  req.resume();
  return json(404, { error: 'not found', path: req.url });
});

server.listen(PORT, () => console.log(`[mock-whisper] Whisper-shaped STT on :${PORT}`));
