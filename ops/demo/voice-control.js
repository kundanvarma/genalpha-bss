/*
 * Voice-control seam for the AI-narrated demo — plan A; the keyboard is plan B.
 *
 * Offline STT (whisper.cpp) listening for spoken control words and emitting one
 * command per line on stdout ("pause" | "resume" | "quit"), which present.js
 * routes into the same control state the keyboard drives. OPT-IN: present.js only
 * spawns this when DEMO_VOICE=1, so the default run needs no mic and no model.
 *
 * WHY no wake-word: a coined word like "genalpha" is unrecognisable to any STT
 * ("Janalfa Paws"), so we match the COMMAND words directly (which transcribe
 * well) with fuzzy variants, and force the language (auto-detect is unreliable on
 * short clips). Say just: "pause" … "resume" … "stop".
 *
 * The engine is a SEAM: STT_MODEL swaps the whisper model (ggml-base.bin =
 * multilingual, best for a Norwegian speaker; ggml-*.en.bin = English-only,
 * slightly sharper on English). STT_LANG forces the language (en | no | auto).
 *
 *   node ops/demo/voice-control.js                 # listen (needs mic permission)
 *   node ops/demo/voice-control.js --selftest "Janalfa Paws"   # -> pause
 */
const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const MODEL = process.env.STT_MODEL || path.join(__dirname, 'models', 'ggml-base.bin');
const LANG = process.env.STT_LANG || 'en';       // 'no' for Norwegian, 'auto' to detect
const DEVICE = process.env.MIC_DEVICE || ':0';   // avfoundation audio device index
const WINDOW = Number(process.env.STT_WINDOW || 1.6); // seconds per listen

// Command words survive STT even when a coined wake-word does not. Order matters:
// check quit (most specific) → resume → pause. English + a few Norwegian synonyms.
function classify(textRaw) {
  const t = ' ' + textRaw.toLowerCase().replace(/[^a-zæøå ]/g, ' ') + ' ';
  if (/\b(quit|exit|end show|shut ?down|avslutt)\b/.test(t)) return 'quit';
  if (/\b(resume|resumes|continue|carry on|proceed|start|play|go on|next|onward|fortsett|videre|start igjen)\b/.test(t)) return 'resume';
  if (/\b(pause|paws|pos|pass|hold|wait|stop|stopp|halt|vent)\b/.test(t)) return 'pause';
  return null;
}

if (process.argv[2] === '--selftest') {
  console.log(classify(process.argv.slice(3).join(' ')) || '(none)');
  process.exit(0);
}

function have(bin) { return spawnSync('sh', ['-c', `command -v ${bin}`]).status === 0; }
if (!have('whisper-cli') || !have('ffmpeg') || !fs.existsSync(MODEL)) {
  console.error('[voice] missing whisper-cli / ffmpeg / model — staying keyboard-only.');
  console.error('[voice] setup: brew install whisper-cpp ffmpeg; model at ' + MODEL);
  process.exit(2);
}

const wav = path.join(os.tmpdir(), 'bss-voice.wav');
let last = '', lastAt = 0;
console.error(`[voice] listening (model=${path.basename(MODEL)}, lang=${LANG}, device=${DEVICE}). Say: pause · resume · stop. Ctrl-C to stop.`);

function tick() {
  // record a short window from the mic (first mic access prompts macOS permission)
  const rec = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y',
    '-f', 'avfoundation', '-i', DEVICE, '-t', String(WINDOW), '-ar', '16000', '-ac', '1', wav]);
  if (rec.status !== 0) { console.error('[voice] mic capture failed (permission? wrong MIC_DEVICE?) — keyboard still works.'); return setTimeout(tick, 800); }
  const out = spawnSync('whisper-cli', ['-m', MODEL, '-f', wav, '-nt', '-l', LANG],
    { encoding: 'utf8' }).stdout || '';
  const cmd = classify(out);
  const now = Date.now();
  if (cmd && !(cmd === last && now - lastAt < 2500)) {
    process.stdout.write(cmd + '\n'); last = cmd; lastAt = now;
    console.error(`[voice] heard "${out.trim().slice(0, 40)}" -> ${cmd}`);
  }
  setImmediate(tick);
}
tick();
