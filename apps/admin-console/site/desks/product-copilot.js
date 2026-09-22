'use strict';

/* ---------------- Product Copilot: chat about a product, create it ---------------- */
/* The owner chats; the intelligence component PROPOSES (specs, prices,
 * offerings, bundles as TMF620 payloads); this panel validates the proposal,
 * shows it as a human card, and on "Create it" applies it in dependency
 * order with the OWNER's token — spec, prices, offerings, bundle links.
 * The model never writes; partial failures roll back in reverse. */
// The chat survives reloads and re-auth redirects: a long model
// conversation must never be lost to a token bounce.
const copilotChat = JSON.parse(sessionStorage.getItem('bss.console.copilotChat') || '[]');
function copilotRemember() {
  sessionStorage.setItem('bss.console.copilotChat', JSON.stringify(copilotChat.slice(-40)));
}

// THE MIC (shared by both copilots): "by talking", literally — speech-to-text
// fills the input and STOPS there. The human reads the transcript and presses
// Send; voice changes the keyboard, never the approval. Two engines, one rule:
// browser Web Speech where the browser has one; otherwise the TENANT's
// bring-your-own STT seam (/ai/v1/transcribe — Whisper-shaped provider, audio
// recorded here, transcribed server-side). Neither bound = no mic shown.
// Inserts the mic button before sendBtn inside bar.
function attachCopilotMic(input, bar, sendBtn) {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (SR) {
    const mic = document.createElement('button');
    mic.className = 'ghost'; mic.id = 'copilot-mic'; mic.type = 'button';
    mic.textContent = '\u{1F3A4}';
    mic.title = 'Speak instead of typing — the transcript lands here, you still press Send';
    mic.dataset.testid = 'copilot-mic';
    let rec = null;
    mic.addEventListener('click', () => {
      if (rec) { rec.stop(); return; } // second tap = stop listening
      rec = new SR();
      rec.lang = document.documentElement.lang || 'en-US';
      rec.interimResults = true; rec.continuous = false;
      mic.textContent = '● listening'; mic.classList.add('mic-live');
      rec.onresult = (e) => {
        input.value = Array.from(e.results)
          .map((r) => (r[0] && r[0].transcript ? r[0].transcript : ''))
          .join(' ').replace(/\s+/g, ' ').trim();
      };
      rec.onerror = (e) => {
        if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
          input.placeholder = 'microphone blocked — type instead';
        }
      };
      rec.onend = () => {
        mic.textContent = '\u{1F3A4}'; mic.classList.remove('mic-live'); rec = null; input.focus();
      };
      rec.start();
    });
    bar.insertBefore(mic, sendBtn);
    return;
  }
  // no browser recognizer — offer the server seam if this tenant bound one
  authFetch('/ai/v1/transcribe/available').then((r) => (r.ok ? r.json() : { available: false }))
    .then(({ available }) => {
      if (!available || !navigator.mediaDevices) return;
      const smic = document.createElement('button');
      smic.className = 'ghost'; smic.id = 'copilot-mic'; smic.type = 'button';
      smic.textContent = '\u{1F3A4}';
      smic.title = 'Speak instead of typing (transcribed by your STT provider) — you still press Send';
      smic.dataset.testid = 'copilot-mic';
      let recorder = null;
      smic.addEventListener('click', async () => {
        if (recorder) { recorder.stop(); return; } // second tap = stop
        try {
          const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
          const chunks = [];
          recorder = new MediaRecorder(stream);
          smic.textContent = '● listening'; smic.classList.add('mic-live');
          recorder.ondataavailable = (e) => chunks.push(e.data);
          recorder.onstop = async () => {
            stream.getTracks().forEach((t) => t.stop());
            smic.textContent = '\u{1F3A4}'; smic.classList.remove('mic-live'); recorder = null;
            const form = new FormData();
            form.append('audio', new Blob(chunks, { type: 'audio/webm' }), 'speech.webm');
            const res = await authFetch('/ai/v1/transcribe', { method: 'POST', body: form });
            if (res.ok) { const { text } = await res.json(); input.value = text || input.value; }
            else { input.placeholder = 'transcription failed — type instead'; }
            input.focus();
          };
          recorder.start();
          setTimeout(() => { if (recorder) recorder.stop(); }, 15000); // hard stop at 15s
        } catch { input.placeholder = 'microphone blocked — type instead'; }
      });
      bar.insertBefore(smic, sendBtn);
    }).catch(() => {});
}

let copilotHost = null; // set while Ask Copilot renders into the reading drawer
function copilotPanel() {
  let panel = document.getElementById('copilot-panel');
  // the tab route renders inline: a panel left inside the reading drawer (Ask Copilot) is evicted first
  if (!copilotHost && panel && panel.closest('#side-drawer')) { panel.remove(); panel = null; }
  if (copilotHost && panel && !copilotHost.contains(panel)) { panel.remove(); panel = null; }
  if (!panel) {
    panel = document.createElement('div');
    panel.id = 'copilot-panel';
    if (copilotHost) copilotHost.append(panel); else document.querySelector('.table-wrap').after(panel);
  }
  panel.hidden = false;
  return panel;
}

async function copilotCatalogContext() {
  const [offerings, prices] = await Promise.all([
    (async () => { const all = []; for (let off = 0; off < 1000; off += 100) { const page = await authFetch(`${API_BASE}/productOffering?limit=100&offset=${off}`).then((r) => r.json()).catch(() => []); all.push(...(Array.isArray(page) ? page : [])); if (!Array.isArray(page) || page.length < 100) break; } return all; })(),
    authFetch(`${API_BASE}/productOfferingPrice?limit=5`).then((r) => r.json()).catch(() => []),
  ]);
  const categories = [...new Set(offerings.flatMap((o) => (o.category || []).map((c) => c.name)).filter(Boolean))];
  const currency = prices.find((p) => p.price?.unit)?.price?.unit || tenantCurrency();
  return {
    categories,
    currency,
    // every name, so "needs Fiber 500" always finds Fiber 500; full rows only for the shelf's first forty (the model's working set)
    names: offerings.map((o) => o.name),
    offerings: offerings.slice(0, 40).map((o) => ({ name: o.name, category: (o.category || [])[0]?.name })),
    all: offerings.map((o) => ({ id: o.id, name: o.name })),
  };
}

/**
 * Deterministic repair of KNOWN-wrong model habits before validation: a
 * non-positive "price" is always a discount in disguise (discounts are
 * pricing rules), and prodSpecCharValueUse must be a list. Everything
 * dropped is reported on the card — repaired, never hidden.
 */
function copilotSanitize(proposal) {
  const notes = [];
  const prices = proposal.prices || [];
  const bad = prices.filter((p) => !(Number(p.price?.value) > 0));
  if (bad.length) {
    proposal.prices = prices.filter((p) => Number(p.price?.value) > 0);
    const badRefs = new Set(bad.map((b) => b.ref));
    for (const o of proposal.offerings || []) {
      o.priceRefs = (o.priceRefs || []).filter((r) => !badRefs.has(r));
    }
    notes.push(`dropped ${bad.length} non-positive "price" — a discount belongs in the pricing rule, which this proposal ${(proposal.pricingRules || []).length ? 'has' : 'is missing'}`);
  }
  for (const p of proposal.prices || []) {
    if (p.prodSpecCharValueUse) {
      // a valid condition is a list of {name, productSpecCharacteristicValue}
      // objects; anything else (strings, prose) is model noise — drop it and
      // say so, the price itself is fine unconditioned
      const valid = Array.isArray(p.prodSpecCharValueUse)
        ? p.prodSpecCharValueUse.filter((c) => c && typeof c === 'object' && !Array.isArray(c) && c.name)
        : [];
      if (valid.length !== (Array.isArray(p.prodSpecCharValueUse) ? p.prodSpecCharValueUse.length : 1)) {
        notes.push(`ignored a malformed condition on price "${p.name}"`);
      }
      if (valid.length) p.prodSpecCharValueUse = valid;
      else delete p.prodSpecCharValueUse;
    }
  }
  return notes;
}

function copilotValidate(proposal, context) {
  const problems = [];
  const offerings = proposal.offerings || [];
  const rules = proposal.pricingRules || [];
  const expRules = proposal.experienceRules || [];
  if (!offerings.length && !rules.length && !expRules.length) {
    problems.push('the proposal creates no offerings');
  }
  for (const er of expRules) {
    if (!er.banner || !String(er.banner).trim()) problems.push(`experience rule "${er.name}" has no banner copy`);
    if (!er.whenInterest || !String(er.whenInterest).trim()) {
      problems.push(`experience rule "${er.name}" names no browsing interest`);
    }
    if (er.pinOffering && !(proposal.offerings || []).some((x) => x.ref === er.pinOffering)
        && !context.offerings.some((x) => x.name === er.pinOffering)) {
      problems.push(`experience rule "${er.name}" pins "${er.pinOffering}", not in this proposal or the catalog`);
    }
  }
  const priceRefs = new Set((proposal.prices || []).map((x) => x.ref));
  const specRefs = new Set((proposal.specs || []).map((x) => x.ref));
  const offeringRefs = new Set(offerings.map((x) => x.ref));
  for (const price of proposal.prices || []) {
    if (!(Number(price.price?.value) > 0)) problems.push(`price "${price.name}" has no positive value`);
  }
  for (const o of offerings) {
    if (!o.name || !String(o.name).trim()) problems.push('an offering has no name');
    if (context.offerings.some((x) => x.name === o.name)) problems.push(`"${o.name}" already exists in the catalog`);
    for (const ref of o.priceRefs || []) {
      if (!priceRefs.has(ref)) problems.push(`offering "${o.name}" references unknown price ${ref}`);
    }
    if (o.specRef && !specRefs.has(o.specRef)) problems.push(`offering "${o.name}" references unknown spec ${o.specRef}`);
    for (const child of o.bundledChildren || []) {
      if (child.offeringRef && !offeringRefs.has(child.offeringRef)) {
        problems.push(`bundle "${o.name}" references unknown offering ${child.offeringRef}`);
      }
      if (child.existingName && !(context.all || context.offerings).some((x) => String(x.name).toLowerCase() === String(child.existingName).toLowerCase())) {
        problems.push(`bundle "${o.name}" references "${child.existingName}", not found in the catalog`);
      }
    }
    const cat = (o.category || [])[0]?.name;
    if (cat && context.categories.length && !context.categories.includes(cat)) {
      problems.push(`category "${cat}" is new — it will be created by use (placement may need a seed)`);
    }
  }
  const offeringRefsAll = new Set(offerings.map((x) => x.ref));
  for (const rule of rules) {
    if (!Number(rule.adjustmentValue)) problems.push(`rule "${rule.name}" has no adjustment value`);
    for (const target of rule.whenCartHas || []) {
      if (!offeringRefsAll.has(target) && !(context.all || context.offerings).some((x) => String(x.name).toLowerCase() === String(target).toLowerCase())) {
        problems.push(`rule "${rule.name}" references "${target}", not in this proposal or the catalog`);
      }
    }
  }
  return problems;
}
