/* Milenage (3GPP TS 35.205/35.206) and EAP-AKA (RFC 4187) primitives — the SIM's
 * arithmetic, shared by the AUC mock (which generates authentication vectors)
 * and the TS.43 device simulator (which answers the challenge like a USIM).
 * Pure Node crypto, no dependencies. Validated against 3GPP TS 35.208 test set 1.
 */
'use strict';

const crypto = require('crypto');

const aes = (key, block) => {
  const c = crypto.createCipheriv('aes-128-ecb', key, null);
  c.setAutoPadding(false);
  return Buffer.concat([c.update(block), c.final()]);
};
const xor = (a, b) => Buffer.from(a.map((x, i) => x ^ b[i]));
const rotl = (buf, bits) => { // rotate a 16-byte block left by a multiple of 8 bits
  const n = (bits / 8) % 16;
  return Buffer.concat([buf.subarray(n), buf.subarray(0, n)]);
};
const cN = (n) => { const c = Buffer.alloc(16); c[15] = n; return c; };

/** OPc from OP (TS 35.206 §4.1). */
function opc(k, op) {
  return xor(aes(k, op), op);
}

/** All Milenage outputs for one (K, OPc, RAND, SQN, AMF). Buffers throughout. */
function milenage(k, opcKey, rand, sqn, amf) {
  const temp = aes(k, xor(rand, opcKey));
  const in1 = Buffer.concat([sqn, amf, sqn, amf]);
  const out1 = xor(aes(k, xor(xor(temp, rotl(xor(in1, opcKey), 64)), cN(0))), opcKey);
  const out2 = xor(aes(k, xor(rotl(xor(temp, opcKey), 0), cN(1))), opcKey);
  const out3 = xor(aes(k, xor(rotl(xor(temp, opcKey), 32), cN(2))), opcKey);
  const out4 = xor(aes(k, xor(rotl(xor(temp, opcKey), 64), cN(4))), opcKey);
  const out5 = xor(aes(k, xor(rotl(xor(temp, opcKey), 96), cN(8))), opcKey);
  return {
    macA: out1.subarray(0, 8), macS: out1.subarray(8, 16),
    res: out2.subarray(8, 16), ak: out2.subarray(0, 6),
    ck: out3, ik: out4, akStar: out5.subarray(0, 6),
  };
}

/** The AUC side: an authentication vector (RAND, AUTN, XRES, CK, IK) for a subscriber. */
function vector(k, opcKey, sqn, amf = Buffer.from('8000', 'hex'), rand = crypto.randomBytes(16)) {
  const m = milenage(k, opcKey, rand, sqn, amf);
  const autn = Buffer.concat([xor(sqn, m.ak), amf, m.macA]);
  return { rand, autn, xres: m.res, ck: m.ck, ik: m.ik };
}

/** The USIM side: verify AUTN, return RES/CK/IK (or null when the MAC does not match). */
function answer(k, opcKey, rand, autn) {
  const temp = aes(k, xor(rand, opcKey));
  const out2 = xor(aes(k, xor(rotl(xor(temp, opcKey), 0), cN(1))), opcKey);
  const ak = out2.subarray(0, 6);
  const sqn = xor(autn.subarray(0, 6), ak);
  const amf = autn.subarray(6, 8);
  const m = milenage(k, opcKey, rand, sqn, amf);
  if (!m.macA.equals(autn.subarray(8, 16))) return null;
  return { res: m.res, ck: m.ck, ik: m.ik, sqn };
}

/* ------------------------------------------------------------------ EAP-AKA */

/** SHA-1 compression function on one 64-byte block with a given 5-word state —
 * FIPS 186-2's G(t, c) as RFC 4187 §7 requires (no padding, no length). */
function sha1Block(state, block) {
  const w = new Array(80);
  for (let i = 0; i < 16; i++) w[i] = block.readUInt32BE(i * 4);
  for (let i = 16; i < 80; i++) { const x = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]; w[i] = ((x << 1) | (x >>> 31)) >>> 0; }
  let [a, b, c, d, e] = state;
  for (let i = 0; i < 80; i++) {
    let f; let k;
    if (i < 20) { f = (b & c) | (~b & d); k = 0x5A827999; }
    else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
    else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
    else { f = b ^ c ^ d; k = 0xCA62C1D6; }
    const t = ((((a << 5) | (a >>> 27)) >>> 0) + (f >>> 0) + e + k + w[i]) >>> 0;
    e = d; d = c; c = ((b << 30) | (b >>> 2)) >>> 0; b = a; a = t;
  }
  return [(state[0] + a) >>> 0, (state[1] + b) >>> 0, (state[2] + c) >>> 0, (state[3] + d) >>> 0, (state[4] + e) >>> 0];
}
const SHA1_IV = [0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0];
const wordsToBuf = (w) => { const b = Buffer.alloc(20); w.forEach((x, i) => b.writeUInt32BE(x >>> 0, i * 4)); return b; };
const add160 = (a, b, plus) => { // (a + b + plus) mod 2^160, big-endian 20-byte buffers
  const out = Buffer.alloc(20); let carry = plus;
  for (let i = 19; i >= 0; i--) { const s = a[i] + b[i] + carry; out[i] = s & 0xff; carry = s >> 8; }
  return out;
};

/** RFC 4187 §7 key derivation: MK = SHA1(Identity|IK|CK); FIPS 186-2 PRF → K_encr, K_aut, MSK, EMSK. */
function deriveKeys(identity, ik, ck) {
  const mk = crypto.createHash('sha1').update(Buffer.concat([Buffer.from(identity, 'utf8'), ik, ck])).digest();
  let xkey = mk; const out = [];
  for (let j = 0; j < 4; j++) {
    for (let i = 0; i < 2; i++) {
      const block = Buffer.alloc(64); xkey.copy(block, 0);
      const w = wordsToBuf(sha1Block(SHA1_IV, block));
      out.push(w);
      xkey = add160(xkey, w, 1);
    }
  }
  const stream = Buffer.concat(out); // 160 bytes
  return { kEncr: stream.subarray(0, 16), kAut: stream.subarray(16, 32), msk: stream.subarray(32, 96), emsk: stream.subarray(96, 160) };
}

const EAP = { REQUEST: 1, RESPONSE: 2, SUCCESS: 3, FAILURE: 4, TYPE_AKA: 23, AKA_CHALLENGE: 1,
  AT_RAND: 1, AT_AUTN: 2, AT_RES: 3, AT_MAC: 11 };

/** Build an EAP-AKA packet (Code, Identifier, Subtype, attributes[{type, value}]) — AT_MAC computed last when kAut given. */
function buildAka(code, identifier, subtype, attrs, kAut) {
  const parts = [];
  for (const a of attrs) parts.push(Buffer.concat([Buffer.from([a.type, (a.value.length + 2) / 4]), a.value]));
  if (kAut) parts.push(Buffer.concat([Buffer.from([EAP.AT_MAC, 5, 0, 0]), Buffer.alloc(16)]));
  const body = Buffer.concat(parts);
  const pkt = Buffer.alloc(8 + body.length);
  pkt[0] = code; pkt[1] = identifier; pkt.writeUInt16BE(pkt.length, 2); pkt[4] = EAP.TYPE_AKA; pkt[5] = subtype; body.copy(pkt, 8);
  if (kAut) {
    const mac = crypto.createHmac('sha1', kAut).update(pkt).digest().subarray(0, 16);
    mac.copy(pkt, pkt.length - 16);
  }
  return pkt;
}

/** Parse an EAP-AKA packet into {code, identifier, subtype, attrs: {type: value}} */
function parseAka(pkt) {
  const out = { code: pkt[0], identifier: pkt[1], length: pkt.readUInt16BE(2), type: pkt[4], subtype: pkt[5], attrs: {} };
  let i = 8;
  while (i + 2 <= pkt.length) {
    const type = pkt[i]; const len = pkt[i + 1] * 4;
    out.attrs[type] = pkt.subarray(i + 2, i + len);
    i += len;
  }
  return out;
}

/** Verify AT_MAC over the packet (the MAC field zeroed) with K_aut. */
function verifyMac(pkt, kAut) {
  const idx = pkt.lastIndexOf(Buffer.from([EAP.AT_MAC, 5, 0, 0]));
  if (idx < 0) return false;
  const copy = Buffer.from(pkt); copy.fill(0, idx + 4, idx + 20);
  const mac = crypto.createHmac('sha1', kAut).update(copy).digest().subarray(0, 16);
  return mac.equals(pkt.subarray(idx + 4, idx + 20));
}

module.exports = { opc, milenage, vector, answer, deriveKeys, buildAka, parseAka, verifyMac, EAP };

/* self-test: 3GPP TS 35.208 test set 1 */
if (require.main === module) {
  const k = Buffer.from('465b5ce8b199b49faa5f0a2ee238a6bc', 'hex');
  const op = Buffer.from('cdc202d5123e20f62b6d676ac72cb318', 'hex');
  const rand = Buffer.from('23553cbe9637a89d218ae64dae47bf35', 'hex');
  const sqn = Buffer.from('ff9bb4d0b607', 'hex'); const amf = Buffer.from('b9b9', 'hex');
  const o = opc(k, op);
  const m = milenage(k, o, rand, sqn, amf);
  const expect = { opc: 'cd63cb71954a9f4e48a5994e37a02baf', macA: '4a9ffac354dfafb3', res: 'a54211d5e3ba50bf',
    ck: 'b40ba9a3c58b2a05bbf0d987b21bf8cb', ik: 'f769bcd751044604127672711c6d3441', ak: 'aa689c648370' };
  const got = { opc: o.toString('hex'), macA: m.macA.toString('hex'), res: m.res.toString('hex'), ck: m.ck.toString('hex'), ik: m.ik.toString('hex'), ak: m.ak.toString('hex') };
  let ok = true;
  for (const key of Object.keys(expect)) { const pass = expect[key] === got[key]; ok = ok && pass; console.log(`${pass ? 'OK ' : 'BAD'} ${key} ${got[key]}`); }
  const v = vector(k, o, sqn, amf, rand); const a = answer(k, o, v.rand, v.autn);
  console.log(a && a.res.equals(v.xres) && a.sqn.equals(sqn) ? 'OK  USIM answers the AUC vector' : 'BAD USIM answer');
  const keys = deriveKeys('0234150999999999@nai.epc.mnc015.mcc234.3gppnetwork.org', v.ik, v.ck);
  const req = buildAka(EAP.REQUEST, 7, EAP.AKA_CHALLENGE, [{ type: EAP.AT_RAND, value: Buffer.concat([Buffer.alloc(2), v.rand]) }, { type: EAP.AT_AUTN, value: Buffer.concat([Buffer.alloc(2), v.autn]) }], keys.kAut);
  const p = parseAka(req);
  console.log(verifyMac(req, keys.kAut) && p.attrs[EAP.AT_RAND].subarray(2).equals(v.rand) ? 'OK  EAP-AKA challenge round-trips with AT_MAC' : 'BAD EAP packet');
  process.exit(ok ? 0 : 1);
}
