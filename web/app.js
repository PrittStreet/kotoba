(() => {
  const KEY = 'kotoba.v1';
  const KEY_API = 'kotoba.apikey';
  const KEY_WS = 'kotoba.workspace';
  const KEY_USAGE = 'kotoba.usage';
  const MAX_ITEMS = 150;
  // Set by the Android app (MainActivity): native microphone, voices and clipboard.
  const NATIVE = window.KotobaAndroid || null;
  // Key and workspace built into a private APK for friends (empty in public builds and in a browser).
  const BUILTIN = (() => {
    try {
      const c = NATIVE ? JSON.parse(NATIVE.config()) : null;
      return c && typeof c.apiKey === 'string' ? c : { apiKey: '', workspace: '' };
    } catch (e) { return { apiKey: '', workspace: '' }; }
  })();
  const $ = (s) => document.querySelector(s);
  const thread = $('#thread'), ta = $('#src'), form = $('#form'), sendBtn = $('#sendBtn');
  const cameraBtn = $('#cameraBtn'), galleryBtn = $('#galleryBtn'), banner = $('#banner'), toastEl = $('#toast');
  const photoPickers = [[cameraBtn, $('#cameraInput')], [galleryBtn, $('#galleryInput')]];
  const favBtn = $('#favBtn'), clearBtn = $('#clearBtn'), setBtn = $('#setBtn'), confirmBar = $('#confirm'), romajiBtn = $('#romajiBtn');
  const micBtns = Array.from(document.querySelectorAll('[data-mic]'));
  const kbdBtns = Array.from(document.querySelectorAll('[data-kbd]'));
  const silenceInput = $('#silenceInput');
  const showEl = $('#show'), settingsEl = $('#settings');
  const keyInput = $('#keyInput'), wsInput = $('#wsInput'), keyStatus = $('#keyStatus');
  const lensView = $('#lensView'), lvScroll = $('#lvScroll'), lvWrap = $('#lvWrap'), lvOrig = $('#lvOrig');
  const sheet = $('#sheet');

  const SV = (d) => '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + d + '</svg>';
  const ICONS = {
    speak: SV('<path d="M4 9.5v5h3.5L12 18V6L7.5 9.5H4z"/><path d="M15.5 9a4.2 4.2 0 0 1 0 6M18.2 6.5a8 8 0 0 1 0 11"/>'),
    copy: SV('<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 0 1 2-2h9"/>'),
    show: SV('<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>'),
    star: SV('<path d="M12 3.6l2.6 5.2 5.8.9-4.2 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.2-4.1 5.8-.9z"/>'),
    trash: SV('<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>'),
    gear: SV('<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>'),
    camera: SV('<path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/>'),
    gallery: SV('<rect x="3.5" y="5" width="17" height="14" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="M20.5 16l-5-5L6 19"/>'),
    send: SV('<path d="M12 19V5M6 11l6-6 6 6"/>'),
    stop: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="1.5" fill="currentColor"/></svg>',
    close: SV('<path d="M6 6l12 12M18 6L6 18"/>'),
    mic: SV('<rect x="9" y="3.5" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v2.5"/>'),
    kbd: SV('<rect x="2.5" y="6" width="19" height="12" rx="2.2"/><path d="M6.5 10h.01M10 10h.01M14 10h.01M17.5 10h.01M8 14.2h8"/>'),
    turn: SV('<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M19.5 4v4.5H15"/>')
  };
  kbdBtns.forEach((b) => { b.innerHTML = ICONS.kbd; });
  micBtns.forEach((b) => b.insertAdjacentHTML('beforeend', '<svg class="ring" aria-hidden="true"><rect pathLength="100"/></svg>'));
  $('#lvClose').innerHTML = ICONS.close;
  $('#lvTurn').innerHTML = ICONS.turn;
  favBtn.innerHTML = ICONS.star;
  clearBtn.innerHTML = ICONS.trash;
  setBtn.innerHTML = ICONS.gear;
  cameraBtn.innerHTML = ICONS.camera;
  galleryBtn.innerHTML = ICONS.gallery;
  $('#closeShow').innerHTML = ICONS.close;
  $('#closeSettings').innerHTML = ICONS.close;

  const EXAMPLES = [
    { id: 'ex1', kind: 'text', src: 'fr', tgt: 'ja', text: 'Est-ce que vous avez des plats sans porc ?',
      tr: '豚肉を使っていない料理はありますか？', ro: 'Butaniku o tsukatte inai ryōri wa arimasu ka?', note: '', status: 'done' },
    { id: 'ex2', kind: 'text', src: 'ja', tgt: 'fr', text: 'すみません、この電車は新宿に止まりますか？',
      tr: 'Excusez-moi, est-ce que ce train s’arrête à Shinjuku ?', ro: '', note: '', status: 'done' }
  ];

  // silence: seconds of silence after which dictation ends (set in the settings)
  // voice: 'female' | 'male' for "Écouter"; kana: rōmaji typed on the Japanese side becomes hiragana
  // started: the examples are only shown before the first translation (then they look like messages)
  const state = { items: [], speaker: 'fr', tier: 'quick', polite: true, romaji: true, silence: 5, voice: 'female', kana: false, started: false, favOnly: false };
  const files = new Map();      // id -> image blob sent to Claude (this session only)
  const photoUrls = new Map();  // id -> sharp object URL of that image (this session only)
  let busy = false, ctl = null, keyProblem = '', memKey = '', memWs = '';
  let unfavAsk = ''; // id of the card asking whether to remove its favourite
  let runStartedAt = 0;
  let listening = null;         // { lang: 'fr' | 'ja', text } while the microphone is on
  let queuedSrc = null;         // language of a dictated sentence waiting for the current translation

  // ---------- storage (per-device) ----------
  function load() {
    try {
      const d = JSON.parse(localStorage.getItem(KEY) || 'null');
      if (!d || typeof d !== 'object') return;
      if (Array.isArray(d.items)) {
        state.items = d.items
          .filter((i) => i && typeof i.id === 'string' && typeof i.tr === 'string')
          .map((i) => i.status === 'pending' ? Object.assign({}, i, { status: i.tr ? 'done' : 'error', err: i.tr ? '' : 'Traduction interrompue.' }) : i);
      }
      if (d.speaker === 'fr' || d.speaker === 'ja') state.speaker = d.speaker;
      if (d.tier === 'quick' || d.tier === 'careful') state.tier = d.tier;
      if (typeof d.polite === 'boolean') state.polite = d.polite;
      if (typeof d.romaji === 'boolean') state.romaji = d.romaji;
      if (typeof d.silence === 'number' && d.silence >= 1 && d.silence <= 15) state.silence = d.silence;
      if (d.voice === 'male' || d.voice === 'female') state.voice = d.voice;
      if (typeof d.kana === 'boolean') state.kana = d.kana;
      state.started = d.started === true || state.items.length > 0 || loadUsage().n > 0;
    } catch (e) { /* storage unavailable */ }
  }
  // Photos are heavy: if storage is full, keep only the most recent ones, then none.
  function save() {
    const n = state.items.length;
    const levels = [
      (i) => i,
      (i, k) => (i.photo && k < n - 6 ? Object.assign({}, i, { photo: '' }) : i),
      (i) => (i.photo || i.thumb ? Object.assign({}, i, { photo: '', thumb: '' }) : i)
    ];
    for (const lv of levels) {
      try {
        const data = { items: state.items.map(lv), speaker: state.speaker, tier: state.tier, polite: state.polite, romaji: state.romaji, silence: state.silence, voice: state.voice, kana: state.kana, started: state.started };
        localStorage.setItem(KEY, JSON.stringify(data));
        return;
      } catch (e) { /* try a lighter copy */ }
    }
  }
  // The key typed in the settings wins; otherwise the one built into the app, if any.
  function storedKey() {
    try { return (localStorage.getItem(KEY_API) || '').trim(); } catch (e) { return memKey; }
  }
  const usingBuiltin = () => !storedKey() && !!BUILTIN.apiKey;
  function getKey() { return storedKey() || BUILTIN.apiKey || ''; }
  function setKey(k) {
    memKey = k;
    try { if (k) localStorage.setItem(KEY_API, k); else localStorage.removeItem(KEY_API); } catch (e) {}
  }
  // Only for keys not tied to one workspace (Console "personal" keys): the workspace each request runs in.
  function storedWorkspace() {
    try { return (localStorage.getItem(KEY_WS) || '').trim(); } catch (e) { return memWs; }
  }
  function getWorkspace() { return usingBuiltin() ? (BUILTIN.workspace || '') : storedWorkspace(); }
  function setWorkspace(w) {
    memWs = w;
    try { if (w) localStorage.setItem(KEY_WS, w); else localStorage.removeItem(KEY_WS); } catch (e) {}
  }
  function forgetPhoto(id) {
    files.delete(id);
    const u = photoUrls.get(id);
    if (u) { try { URL.revokeObjectURL(u); } catch (e) {} photoUrls.delete(id); }
  }
  // The conversation: what is shown, and the context of the next translations. Clearing it keeps the
  // favourites apart (out: true): shown only with the star, never used as context.
  const inConv = (i) => !i.out;
  const convItems = () => state.items.filter(inConv);
  function trimItems() {
    while (state.items.length > MAX_ITEMS) {
      const k = state.items.findIndex((i) => !i.fav);
      if (k < 0) break;
      forgetPhoto(state.items[k].id);
      state.items.splice(k, 1);
    }
  }

  // ---------- helpers ----------
  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  const newId = () => 'i' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  let toastTimer;
  function toast(msg, ms) {
    toastEl.textContent = msg;
    toastEl.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toastEl.hidden = true; }, ms || 2200);
  }
  const nearBottom = () => thread.scrollHeight - thread.scrollTop - thread.clientHeight < 140;
  const toBottom = () => { thread.scrollTop = thread.scrollHeight; };

  const JA_RE = /[぀-ヿ㐀-䶿一-鿿ｦ-ﾟ]/;
  const FR_RE = /[àâäçéèêëîïôöùûüœæ]/i;
  function detectSrc(text) {
    if (JA_RE.test(text)) return 'ja';
    if (state.speaker === 'ja' && !FR_RE.test(text)) return 'ja'; // rōmaji typed on the Japanese side
    return 'fr';
  }

  function copyFor(code) {
    switch (code) {
      case 'no_key': return 'Ajoute ta clé API Claude dans les réglages pour traduire.';
      case 'bad_key': return 'Clé API refusée. Vérifie-la dans les réglages.';
      case 'no_credit': return 'Plus de crédit sur ton compte Claude : recharge-le sur platform.claude.com.';
      case 'needs_workspace': return 'Ta clé n’est liée à aucun workspace : indique l’ID du workspace dans les réglages.';
      case 'bad_workspace': return 'Workspace introuvable pour cette clé : vérifie son ID dans les réglages.';
      case 'forbidden': return 'Cette clé n’a pas accès à ce modèle.';
      case 'not_found': return 'Modèle introuvable : Kotoba a sans doute besoin d’une mise à jour.';
      case 'rate_limited': return 'Trop de demandes d’un coup. Réessaie dans un moment.';
      case 'overloaded': return 'Claude est surchargé. Réessaie dans un instant.';
      case 'server': return 'Erreur chez Claude. Touche Réessayer.';
      case 'network': return 'Pas de connexion. Vérifie le réseau puis touche Réessayer.';
      case 'refused': return 'Claude n’a pas traduit ce texte. Reformule-le.';
      case 'too_large': return 'Trop long ou trop lourd. Coupe le texte ou essaie une autre photo.';
      case 'image_rejected': return 'Format de photo non pris en charge. Essaie une autre image.';
      case 'photo_missing': return 'Photo introuvable. Reprends-la.';
      case 'cancelled': return 'Traduction arrêtée.';
      default: return 'La demande a échoué. Réessaie.';
    }
  }
  const KEY_CODES = new Set(['no_key', 'bad_key', 'no_credit', 'needs_workspace', 'bad_workspace']);
  function syncBanner() {
    const msg = !getKey() ? 'Pour traduire, ajoute ta clé API Claude.' : (keyProblem ? copyFor(keyProblem) : '');
    $('#bannerText').textContent = msg;
    banner.hidden = !msg;
  }

  // ---------- Claude (Messages API, called straight from the phone with Ronan's key) ----------
  const API = 'https://api.anthropic.com/v1/';
  const MODEL_QUICK = 'claude-haiku-5-5';
  const MODEL_CAREFUL = 'claude-sonnet-5-5';
  // $ per million tokens (input, output): only used to show an estimate in the settings.
  const PRICES = { [MODEL_QUICK]: [0.10, 0.50], [MODEL_CAREFUL]: [2, 10] };
  const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp'];

  function apiHeaders(key, workspace) {
    const h = {
      'x-api-key': key,
      'anthropic-version': '2023-06-01',
      'anthropic-dangerous-direct-browser-access': 'true'
    };
    if (workspace) h['anthropic-workspace-id'] = workspace;
    return h;
  }
  function apiErr(code, extra) {
    const e = new Error(code);
    e.api = true;
    e.code = code;
    return Object.assign(e, extra);
  }
  function errCode(status, err) {
    const type = (err && err.type) || '';
    const msg = (err && err.message) || '';
    if (status === 401 || type === 'authentication_error') return 'bad_key';
    if (status === 402 || type === 'billing_error' || /credit balance/i.test(msg)) return 'no_credit';
    if (/workspace/i.test(msg)) return /not found/i.test(msg) ? 'bad_workspace' : 'needs_workspace';
    if (status === 403 || type === 'permission_error') return 'forbidden';
    if (status === 404 || type === 'not_found_error') return 'not_found';
    if (status === 413 || type === 'request_too_large') return 'too_large';
    if (status === 429 || type === 'rate_limit_error') return 'rate_limited';
    if (status === 529 || type === 'overloaded_error') return 'overloaded';
    if (status >= 500 || type === 'api_error') return 'server';
    if (/image/i.test(msg)) return 'image_rejected';
    return 'failed';
  }
  // Streams one answer; onText gets the whole text so far and the new piece.
  async function claude({ model, content, maxTokens, signal, onText }) {
    const key = getKey();
    if (!key) throw apiErr('no_key');
    const headers = Object.assign({ 'content-type': 'application/json' }, apiHeaders(key, getWorkspace()));
    const body = {
      model,
      max_tokens: maxTokens,
      stream: true,
      output_config: { effort: 'low' },
      messages: [{ role: 'user', content }]
    };
    if (model === MODEL_CAREFUL) {
      // If a safety filter declines, the API answers with another model instead of stopping.
      body.fallbacks = 'default';
      headers['anthropic-beta'] = 'server-side-fallback-2026-07-01';
    }
    let res;
    try {
      res = await fetch(API + 'messages', { method: 'POST', headers, body: JSON.stringify(body), signal });
    } catch (e) {
      throw apiErr(signal && signal.aborted ? 'cancelled' : 'network');
    }
    if (!res.ok) {
      let err = null;
      try { err = (await res.json()).error; } catch (e) {}
      throw apiErr(errCode(res.status, err), { detail: (err && err.message) || '' });
    }
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '', text = '', stop = '', served = model, inTok = 0, outTok = 0;
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true }).replace(/\r/g, '');
        let k;
        while ((k = buf.indexOf('\n\n')) >= 0) {
          const data = buf.slice(0, k).split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trim()).join('');
          buf = buf.slice(k + 2);
          if (!data) continue;
          let ev;
          try { ev = JSON.parse(data); } catch (e) { continue; }
          if (ev.type === 'message_start' && ev.message) {
            served = ev.message.model || served;
            inTok = (ev.message.usage && ev.message.usage.input_tokens) || 0;
          } else if (ev.type === 'content_block_delta' && ev.delta && ev.delta.type === 'text_delta') {
            text += ev.delta.text;
            onText(text, ev.delta.text);
          } else if (ev.type === 'message_delta') {
            if (ev.delta && ev.delta.stop_reason) stop = ev.delta.stop_reason;
            if (ev.usage) {
              if (ev.usage.output_tokens) outTok = ev.usage.output_tokens;
              if (ev.usage.input_tokens) inTok = ev.usage.input_tokens;
            }
          } else if (ev.type === 'error') {
            throw apiErr(errCode(0, ev.error));
          }
        }
      }
    } catch (e) {
      if (e && e.api) { e.text = text; throw e; }
      throw apiErr(signal && signal.aborted ? 'cancelled' : 'network', { text });
    }
    addUsage(served, inTok, outTok);
    if (stop === 'refusal') throw apiErr('refused', { text });
    return { text, truncated: stop === 'max_tokens' };
  }
  // A free call that only checks the key (and workspace). Returns { code, detail }, code '' when it works.
  async function testKey(key, workspace) {
    try {
      const res = await fetch(API + 'models?limit=1', { headers: apiHeaders(key, workspace) });
      if (res.ok) return { code: '', detail: '' };
      let err = null;
      try { err = (await res.json()).error; } catch (e) {}
      return { code: errCode(res.status, err), detail: (err && err.message) || ('HTTP ' + res.status) };
    } catch (e) { return { code: 'network', detail: '' }; }
  }

  function loadUsage() {
    try {
      const u = JSON.parse(localStorage.getItem(KEY_USAGE) || 'null');
      if (u && typeof u.cost === 'number' && typeof u.n === 'number') return u;
    } catch (e) {}
    return { since: Date.now(), n: 0, cost: 0 };
  }
  function addUsage(model, inTok, outTok) {
    const p = PRICES[model] || PRICES[MODEL_CAREFUL];
    const u = loadUsage();
    u.n += 1;
    u.cost += (inTok * p[0] + outTok * p[1]) / 1e6;
    try { localStorage.setItem(KEY_USAGE, JSON.stringify(u)); } catch (e) {}
  }

  function textPrompt(it) {
    const toJa = it.tgt === 'ja';
    const idx = state.items.indexOf(it);
    const ctx = state.items.slice(0, idx < 0 ? state.items.length : idx)
      .filter((x) => inConv(x) && x.kind === 'text' && x.status === 'done' && x.tr)
      .slice(-3)
      .map((x) => '- ' + x.src.toUpperCase() + ': ' + x.text + '  →  ' + x.tgt.toUpperCase() + ': ' + x.tr)
      .join('\n');
    // French side: the user often describes what they want ("demande au pharmacien s'il a…"):
    // Claude speaks for them instead of translating the request word for word.
    const lines = [
      'You are the personal interpreter of a French speaker living in Japan (the user), in their conversations with Japanese people: shops, pharmacies, doctors, stations, restaurants, offices, neighbours.'
    ];
    if (toJa) {
      lines.push(
        'The user typed or dictated the French text below. It is either the sentence they want to say, or a description of what they want to say, ask or get ("demande au pharmacien s’il a…", "dis-lui que…", "je voudrais savoir si…", "comment je dis…").',
        'Write the Japanese the user should say or show to the Japanese person: speaking as the user, in the first person, directly to that person, the way a skilled interpreter would put their request.',
        '',
        'Rules:',
        '- Act on the user’s instructions instead of translating them: leave out what is addressed to you ("peux-tu", "fais-moi une demande", "s’il te plaît" said to you), and speak to the person directly rather than about them ("the pharmacist").',
        '- Natural, idiomatic Japanese for the situation, not a word-by-word translation.',
        '- Name things the way Japanese people know them: for a foreign brand or product, say what kind of product it is in Japan (the brand name can follow).',
        '- Keep names, numbers, prices and times; add nothing the user did not ask for.',
        state.polite
          ? '- Japanese register: polite です／ます form, right for strangers, shop and restaurant staff, colleagues.'
          : '- Japanese register: casual plain form (タメ口), as between close friends.'
      );
    } else {
      lines.push(
        'Below is what a Japanese person said or wrote to the user. Translate it into natural French so the user understands what they mean: the meaning, not word by word, using the conversation so far to understand what they refer to.',
        '',
        'Rules:',
        '- Keep names, numbers, prices, times and the tone.'
      );
      if (!JA_RE.test(it.text)) lines.push('- The source is Japanese written in rōmaji; read it as Japanese.');
    }
    if (it.spoken) lines.push('- The text comes from speech recognition: fix obvious recognition mistakes and missing punctuation silently.');
    lines.push('', 'Output format, exactly, with nothing else (no quotes, no labels, no Markdown):', toJa ? 'The Japanese to say.' : 'The French translation.');
    if (toJa && state.romaji) lines.push('Then a line containing only @@R, then the Hepburn rōmaji reading of your Japanese, with macrons (ō, ū).');
    if (toJa) lines.push('Then a line containing only @@F, then a literal French translation of your Japanese, as close to word for word as French allows, so the user can check exactly what it says.');
    lines.push(toJa
      ? 'Then, ONLY if there is an important nuance or cultural pitfall the user should know, a line containing only @@N, then one short sentence in French. Otherwise write nothing more.'
      : 'Then, ONLY if there is an important nuance, implied meaning or cultural point the user should know, a line containing only @@N, then one short sentence in French. Otherwise write nothing more.');
    if (ctx) lines.push('', 'Earlier in this conversation (context only, do not translate):', ctx);
    lines.push('', toJa ? 'What the user typed:' : 'What the Japanese person said:', '"""', it.text, '"""');
    return lines.join('\n');
  }
  // Photo translation drawn over the original text, Google Lens style: Claude streams one JSON block per line.
  function lensPrompt() {
    return [
      'This photo was taken in Japan by a French speaker who cannot read Japanese.',
      'Translate the text visible in it into French, Google Lens style: each French translation will be drawn over the original text, inside the same box.',
      '',
      'Reply with one JSON object per line and nothing else: no Markdown, no code fence, no comments.',
      'Line 1: {"title": "<what the photo shows, 2 to 6 words in French>", "turn": <0, 90, 180 or 270>}',
      'turn: how far the whole photo is turned clockwise from upright, judged from most of its text. Phones often save a photo sideways (taken in landscape, or pointing down at a machine or a table). 0: the text reads normally. 90: horizontal writing runs from top to bottom (tops of the letters face right). 270: it runs from bottom to top (tops of the letters face left). 180: upside down. Japanese written vertically (縦書き) with upright characters is upright: 0.',
      'Then one line per text block, in reading order:',
      '{"b": [x0, y0, x1, y1], "r": <0, 90, 180 or 270>, "o": "<original text>", "t": "<French translation>"}',
      'b is the bounding box of the ORIGINAL text as integers from 0 to 1000 measured on the whole image as it is (not turned): x goes from the left edge (0) to the right edge (1000), y from the top edge (0) to the bottom edge (1000). Keep each box tight around its text.',
      'r is the direction the original text runs in the image as it is, in degrees clockwise: 0 left to right, 90 top to bottom (vertical Japanese, or text turned a quarter clockwise), 180 upside down, 270 bottom to top. The French is drawn in that same direction.',
      '',
      'Rules:',
      '- One block = one line of text, or one cell, label or heading that belongs together. Never merge text that is far apart.',
      '- Translate every text that is not French (Japanese, English, Chinese…). Skip lone numbers, times and prices, and text already in French: they stay visible on the photo.',
      '- Keep translations short so they fit in about the same space. Write station and place names in rōmaji (e.g. Kinshichō).',
      '- At most 80 blocks. If there is more text, keep what matters most to a traveller: titles, names, headings, instructions, warnings.'
    ].join('\n');
  }
  // 0, 90, 180 or 270: the nearest quarter turn (degrees clockwise).
  function quarter(v) {
    const n = Number(v);
    return isFinite(n) ? ((Math.round(n / 90) * 90) % 360 + 360) % 360 : 0;
  }
  function eatLensLine(it, raw) {
    let ln = String(raw).trim().replace(/,\s*$/, '');
    if (!ln || ln[0] !== '{') return false;
    let o;
    try { o = JSON.parse(ln); } catch (e) { return false; }
    if (!o || typeof o !== 'object') return false;
    if (typeof o.title === 'string') { it.title = o.title.trim(); it.turn = quarter(o.turn); return true; }
    if (!Array.isArray(o.b) || o.b.length !== 4 || typeof o.t !== 'string' || !o.t.trim()) return false;
    let b = o.b.map(Number);
    if (b.some((v) => !isFinite(v))) return false;
    if (Math.max.apply(null, b) <= 1.001) b = b.map((v) => v * 1000);
    b = b.map((v) => Math.min(1000, Math.max(0, v)));
    let [x0, y0, x1, y1] = b;
    if (x1 < x0) [x0, x1] = [x1, x0];
    if (y1 < y0) [y0, y1] = [y1, y0];
    if (x1 - x0 < 6) x1 = Math.min(1000, x0 + 6);
    if (y1 - y0 < 6) y1 = Math.min(1000, y0 + 6);
    const blk = { b: [x0, y0, x1, y1].map(Math.round), o: typeof o.o === 'string' ? o.o.trim() : '', t: o.t.trim() };
    // no direction given: the photo's own (most of its text runs that way)
    const r = o.r === undefined || o.r === null ? (it.turn || 0) : quarter(o.r);
    if (r) blk.r = r;
    it.blocks.push(blk);
    return true;
  }
  function lensText(it) {
    return (it.title ? it.title + '\n' : '') + (it.blocks || []).map((b) => (b.o ? b.o + ' → ' : '') + b.t).join('\n');
  }
  function finishLens(it, pendingLine) {
    if (pendingLine && pendingLine.trim()) eatLensLine(it, pendingLine);
    if (it.blocks.length) it.tr = lensText(it);
    else it.tr = (it.raw || '').trim(); // Claude ignored the format: keep its text as a plain list
  }
  // Sections of a text answer: translation, then @@R rōmaji, @@F literal French, @@N note.
  const SECTION = { R: 'ro', F: 'lit', N: 'note' };
  function parseOut(t) {
    const o = { tr: '', ro: '', lit: '', note: '' };
    const re = /@@([RFN])/g;
    let cur = 'tr', last = 0, m;
    while ((m = re.exec(t))) { o[cur] += t.slice(last, m.index); cur = SECTION[m[1]]; last = re.lastIndex; }
    o[cur] += t.slice(last).replace(/@{1,2}$/, '');
    for (const k in o) o[k] = o[k].trim();
    return o;
  }
  function applyText(it, text) {
    if (it.kind === 'image') { it.tr = text.trim(); return; }
    const o = parseOut(text);
    it.tr = o.tr; it.ro = o.ro; it.lit = o.lit; it.note = o.note;
  }
  function blobToBase64(blob) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result).slice(String(r.result).indexOf(',') + 1));
      r.onerror = () => reject(r.error);
      r.readAsDataURL(blob);
    });
  }

  async function run(it) {
    if (busy) return;
    busy = true;
    runStartedAt = Date.now();
    setSending(true);
    it.status = 'pending'; it.err = '';
    if (it.kind === 'text') { it.tr = ''; it.ro = ''; it.lit = ''; it.note = ''; } else { it.tr = ''; it.blocks = []; it.title = ''; it.raw = ''; }
    updateCard(it);
    ctl = new AbortController();
    const req = { signal: ctl.signal };
    let lensPending = '';
    try {
      if (it.kind === 'image') {
        const f = files.get(it.id);
        if (!f) throw apiErr('photo_missing');
        const type = f.type || 'image/jpeg';
        if (!IMAGE_TYPES.includes(type)) throw apiErr('image_rejected');
        const data = await blobToBase64(f);
        req.model = MODEL_CAREFUL;
        req.maxTokens = 8192;
        req.content = [
          { type: 'image', source: { type: 'base64', media_type: type, data } },
          { type: 'text', text: lensPrompt() }
        ];
        req.onText = (text, delta) => {
          it.raw = text;
          const lines = (lensPending + delta).split('\n');
          lensPending = lines.pop();
          let added = false;
          lines.forEach((l) => { if (eatLensLine(it, l)) added = true; });
          if (added) { it.tr = lensText(it); lensProgress(it); }
        };
      } else {
        req.model = state.tier === 'careful' ? MODEL_CAREFUL : MODEL_QUICK;
        req.maxTokens = 2048;
        req.content = textPrompt(it);
        req.onText = (text) => { applyText(it, text); updateCard(it); };
      }
      const r = await claude(req);
      if (it.kind === 'image') { it.raw = r.text; finishLens(it, lensPending); }
      else applyText(it, r.text);
      it.status = 'done';
      if (keyProblem) { keyProblem = ''; syncBanner(); }
      if (r.truncated && it.kind === 'text') it.note = (it.note ? it.note + ' ' : '') + '(Réponse coupée : texte trop long.)';
    } catch (e) {
      const code = (e && e.api && e.code) || 'failed';
      if (code === 'refused') { it.tr = ''; it.ro = ''; it.lit = ''; it.note = ''; if (it.kind === 'image') { it.blocks = []; it.title = ''; } }
      else if (it.kind === 'image') finishLens(it, lensPending);
      else if (e && typeof e.text === 'string' && e.text) applyText(it, e.text);
      if (code === 'cancelled') {
        it.status = it.tr ? 'done' : 'error';
        it.err = it.tr ? '' : copyFor(code);
      } else {
        it.status = 'error';
        it.err = copyFor(code);
        if (KEY_CODES.has(code)) { keyProblem = code === 'no_key' ? '' : code; syncBanner(); }
      }
    } finally {
      busy = false; ctl = null;
      setSending(false);
      updateCard(it);
      save();
      if (queuedSrc) {
        const src = queuedSrc;
        queuedSrc = null;
        if (ta.value.trim()) submit(src, true);
      }
    }
  }

  // ---------- rendering ----------
  function dots() {
    const d = el('span', 'dots');
    d.setAttribute('aria-label', 'Traduction en cours');
    d.append(el('i'), el('i'), el('i'));
    return d;
  }
  function actBtn(act, label, on) {
    const b = el('button', 'act' + (on ? ' on' : ''));
    b.type = 'button';
    b.dataset.act = act;
    b.innerHTML = ICONS[act === 'fav' ? 'star' : act];
    b.append(el('span', null, label));
    if (act === 'fav') b.setAttribute('aria-pressed', String(!!on));
    return b;
  }
  function imageBody(text) {
    const wrap = el('div', 'rows');
    text.split('\n').map((s) => s.replace(/^\s*[-*•]\s*/, '').trim()).filter(Boolean).forEach((ln, i) => {
      const k = ln.indexOf('→');
      if (k > 0) {
        const r = el('div', 'row');
        const o = el('span', 'orig', ln.slice(0, k).trim());
        o.lang = 'ja';
        r.append(o, el('span', 'trl', ln.slice(k + 1).trim()));
        wrap.append(r);
      } else {
        wrap.append(el('p', i === 0 ? 'lead' : 'plain', ln));
      }
    });
    return wrap;
  }
  // Under a Japanese translation: the rōmaji (JA) or what the Japanese says word for word (FR).
  function readingLine(it) {
    const fr = it.view === 'fr' && !!it.lit;
    const p = el('p', 'ro');
    const t = el('span', null, fr ? it.lit : it.ro);
    t.lang = fr ? 'fr' : 'ja-Latn';
    p.append(t);
    if (it.lit && it.status !== 'pending') {
      const g = el('span', 'rt');
      g.setAttribute('role', 'group');
      g.setAttribute('aria-label', 'Lecture ou sens mot à mot');
      [['ja', 'JA', 'Lecture en rōmaji'], ['fr', 'FR', 'Ce que dit le japonais, mot à mot']].forEach(([v, label, name]) => {
        const b = el('button', null, label);
        b.type = 'button';
        b.dataset.act = 'view';
        b.dataset.view = v;
        b.setAttribute('aria-label', name);
        b.setAttribute('aria-pressed', String((fr ? 'fr' : 'ja') === v));
        g.append(b);
      });
      p.append(g);
    }
    return p;
  }
  function buildCard(it, example) {
    const c = el('article', 'card' + (example ? ' example' : ''));
    c.dataset.id = it.id;
    if (example) c.append(el('span', 'badge', 'Exemple'));
    const head = el('div', 'src');
    const photo = it.kind === 'image' ? photoSrc(it) : '';
    const asLens = it.kind === 'image' && photo && ((it.blocks && it.blocks.length) || !it.tr);
    if (it.kind === 'image') {
      head.append(el('span', 'tag ja', 'PHOTO'));
      if (!asLens && it.thumb) { const img = new Image(); img.src = it.thumb; img.alt = 'Photo envoyée'; img.className = 'thumb'; head.append(img); }
      head.append(el('p', 'ptitle', it.title || (it.status === 'pending' ? 'Lecture de la photo…' : 'Texte de la photo')));
    } else {
      head.append(el('span', 'tag ' + it.src, it.src === 'ja' ? 'JA' : 'FR'));
      const p = el('p', null, it.text);
      p.lang = it.src;
      head.append(p);
    }
    c.append(head);
    const out = el('div', 'out');
    out.lang = it.kind === 'image' ? 'fr' : it.tgt;
    if (it.kind === 'image') {
      if (asLens) out.append(buildLens(it, photo));
      else if (it.tr) out.append(imageBody(it.tr));
    } else {
      if (it.tr) out.append(el('p', 'tr', it.tr));
      if (it.ro || it.lit) out.append(readingLine(it));
      if (it.note) { const n = el('p', 'note', it.note); n.lang = 'fr'; out.append(n); }
    }
    if (it.status === 'pending' && !it.tr && !asLens) out.append(dots());
    if (out.childNodes.length) c.append(out);
    if (it.status === 'error') {
      const e = el('div', 'err');
      e.append(el('span', null, it.err || 'La traduction a échoué.'));
      if (it.kind === 'text' || files.has(it.id)) {
        const b = el('button', 'txt-btn', 'Réessayer');
        b.type = 'button';
        b.dataset.act = 'retry';
        e.append(b);
      }
      c.append(e);
    }
    if (!example && it.status !== 'pending' && it.tr) {
      const a = el('div', 'acts');
      if (it.kind === 'text' && tts) a.append(actBtn('speak', 'Écouter'));
      a.append(actBtn('copy', 'Copier'));
      if (it.kind === 'text') a.append(actBtn('show', 'Montrer'));
      a.append(actBtn('fav', state.favOnly ? 'Supprimer le favori' : (it.fav ? 'Gardé' : 'Garder'), it.fav));
      c.append(a);
      if (unfavAsk === it.id && it.fav) {
        const q = el('div', 'ask');
        q.append(el('span', null, it.out ? 'Supprimer ce favori ? Il n’est plus dans la conversation : il sera effacé.' : 'Supprimer ce favori ? Le message reste dans la conversation.'));
        const no = el('button', 'txt-btn', 'Annuler');
        no.type = 'button'; no.dataset.act = 'unfav-no';
        const yes = el('button', 'txt-btn danger', 'Supprimer');
        yes.type = 'button'; yes.dataset.act = 'unfav-yes';
        q.append(no, yes);
        c.append(q);
      }
    }
    return c;
  }
  // ---------- photo translated in place ----------
  const photoSrc = (it) => photoUrls.get(it.id) || it.photo || '';
  const ro = 'ResizeObserver' in window ? new ResizeObserver((entries) => entries.forEach((en) => fitStage(en.target))) : null;
  function fitOv(ov) {
    const w = ov.clientWidth, h = ov.clientHeight;
    const span = ov.firstChild;
    if (w < 2 || h < 2 || !span) return;
    // Text running up or down the photo: the lines follow the box's height.
    const side = ov.dataset.r === '90' || ov.dataset.r === '270';
    const room = side ? w : h;
    if (side) span.style.width = h + 'px';
    let size = Math.min(48, Math.max(6, room * 0.78));
    ov.style.fontSize = size + 'px';
    let n = 0;
    // shrink rather than break words: stop when the text fits the box both ways
    while ((span.scrollWidth > span.clientWidth + 1 || span.offsetHeight > room + 1) && size > 6 && n++ < 24) {
      size = Math.max(6, size * 0.88);
      ov.style.fontSize = size + 'px';
    }
  }
  function fitStage(st) { st.querySelectorAll('.ov').forEach(fitOv); }
  function ovEl(b, i) {
    const ov = el('button', 'ov');
    ov.type = 'button';
    ov.dataset.act = 'block';
    ov.dataset.bi = String(i);
    ov.style.left = b.b[0] / 10 + '%';
    ov.style.top = b.b[1] / 10 + '%';
    ov.style.width = (b.b[2] - b.b[0]) / 10 + '%';
    ov.style.height = (b.b[3] - b.b[1]) / 10 + '%';
    ov.setAttribute('aria-label', b.t);
    const span = el('span', null, b.t);
    if (b.r) { ov.dataset.r = String(b.r); span.style.transform = 'rotate(' + b.r + 'deg)'; }
    ov.append(span);
    return ov;
  }
  // turn: how the photo is turned (said by Claude, or the "turn" button). It is shown turned back,
  // so that its text reads upright; the boxes stay on the photo as it is.
  const turnOf = (it) => (it.turn === 90 || it.turn === 180 || it.turn === 270 ? it.turn : 0);
  const photoRatio = (it) => (it.w && it.h ? it.w / it.h : 0.75);
  const shownRatio = (it) => (turnOf(it) % 180 ? 1 / photoRatio(it) : photoRatio(it));
  function buildStage(it, src, fill) {
    const turn = turnOf(it), r = photoRatio(it), shown = shownRatio(it);
    const frame = el('div', 'frame');
    frame.dataset.turn = String(turn);
    frame.style.aspectRatio = shown.toFixed(4);
    if (!fill) frame.style.width = 'min(100%, ' + (62 * shown).toFixed(2) + 'vh)';
    const st = el('div', 'stage' + (it.status === 'pending' ? ' scan' : ''));
    st.style.aspectRatio = r.toFixed(4);
    if (turn) {
      // Sideways: the photo's width is the frame's height (frame height = frame width × r).
      st.style.width = turn % 180 ? (r * 100).toFixed(3) + '%' : '100%';
      st.style.left = '50%';
      st.style.top = '50%';
      st.style.transform = 'translate(-50%, -50%) rotate(' + (-turn) + 'deg)';
    }
    const img = new Image();
    img.src = src;
    img.alt = it.title || 'Photo';
    img.decoding = 'async';
    const layer = el('div', 'layer');
    (it.blocks || []).forEach((b, i) => layer.append(ovEl(b, i)));
    st.append(img, layer);
    frame.append(st);
    if (ro) ro.observe(st); else requestAnimationFrame(() => fitStage(st));
    return frame;
  }
  function lensStatus(it) {
    const n = (it.blocks || []).length;
    const s = n > 1 ? 's' : '';
    if (it.status === 'pending') return n ? n + ' zone' + s + ' traduite' + s + '…' : 'Lecture de la photo…';
    if (!n) return it.status === 'error' ? '' : 'Aucun texte à traduire trouvé.';
    return n + ' zone' + s + ' · touche un texte pour le lire';
  }
  function buildLens(it, src) {
    const wrap = el('div', 'lens');
    wrap.append(buildStage(it, src, false));
    const bar = el('div', 'lensbar');
    bar.append(el('span', 'status', lensStatus(it)));
    if (it.status !== 'pending') {
      const t = el('button', 'icon-btn turn');
      t.type = 'button'; t.dataset.act = 'turn'; t.setAttribute('aria-label', 'Tourner la photo');
      t.innerHTML = ICONS.turn;
      bar.append(t);
    }
    if (it.status !== 'pending' && it.blocks && it.blocks.length) {
      const o = el('button', 'chip', 'Original');
      o.type = 'button'; o.dataset.act = 'orig'; o.setAttribute('aria-pressed', 'false');
      const f = el('button', 'txt-btn', 'Plein écran');
      f.type = 'button'; f.dataset.act = 'lens';
      bar.append(o, f);
    }
    wrap.append(bar);
    if (it.status !== 'pending' && it.blocks && it.blocks.length) {
      const d = el('details', 'found');
      d.append(el('summary', null, 'Texte détecté (' + it.blocks.length + ')'));
      d.append(imageBody('\n' + it.blocks.map((b) => (b.o ? b.o + ' → ' : '') + b.t).join('\n')));
      wrap.append(d);
    }
    return wrap;
  }
  // While Claude streams, add each new block to the photo without rebuilding the card.
  function lensProgress(it) {
    const card = thread.querySelector('.card[data-id="' + it.id + '"]');
    const layer = card && card.querySelector('.layer');
    const frame = card && card.querySelector('.frame');
    // the first line says how the photo is turned: show it the right way up
    if (!layer || !frame || frame.dataset.turn !== String(turnOf(it))) { updateCard(it); return; }
    for (let i = layer.children.length; i < it.blocks.length; i++) {
      const ov = ovEl(it.blocks[i], i);
      layer.append(ov);
      fitOv(ov);
    }
    const st = card.querySelector('.lensbar .status');
    if (st) st.textContent = lensStatus(it);
    const t = card.querySelector('.ptitle');
    if (t && it.title) t.textContent = it.title;
  }

  function updateCard(it) {
    const old = thread.querySelector('.card[data-id="' + it.id + '"]');
    if (!old) return;
    const pin = nearBottom();
    old.replaceWith(buildCard(it));
    if (pin) toBottom();
  }
  function render() {
    thread.textContent = '';
    unfavAsk = '';
    const conv = convItems();
    if (!conv.length && !state.favOnly) {
      thread.append(el('p', 'intro', stt
        ? 'Touche le micro « Français » ou « 日本語 » et parle : la traduction arrive toute seule à la fin de la phrase. Le petit bouton clavier à côté sert à écrire dans cette langue. Tu peux aussi prendre en photo un menu ou un panneau.'
        : 'Écris ou dicte une phrase, ou prends en photo un menu ou un panneau : la traduction s’affiche directement sur l’image. Le japonais est reconnu tout seul.'));
      if (!state.started) EXAMPLES.forEach((x) => thread.append(buildCard(x, true)));
      return;
    }
    const list = state.favOnly ? state.items.filter((i) => i.fav) : conv;
    if (!list.length) {
      thread.append(el('p', 'intro', 'Aucun favori pour l’instant. Touche « Garder » sous une traduction pour la retrouver ici.'));
      return;
    }
    if (state.favOnly) thread.append(el('p', 'intro', 'Tes favoris, à part de la conversation : ils restent quand tu l’effaces et ne servent pas de contexte aux traductions.'));
    list.forEach((it) => thread.append(buildCard(it)));
    if (state.favOnly) thread.scrollTop = 0; else toBottom();
  }
  function setSending(on) {
    sendBtn.innerHTML = on ? ICONS.stop : ICONS.send;
    sendBtn.classList.toggle('stop', on);
    sendBtn.setAttribute('aria-label', on ? 'Arrêter' : 'Traduire');
  }
  function syncPrefs() {
    document.querySelectorAll('[data-pref]').forEach((b) => {
      const v = b.dataset.pref === 'tier' ? state.tier : (state.polite ? '1' : '0');
      b.setAttribute('aria-pressed', String(b.dataset.val === v));
    });
    romajiBtn.setAttribute('aria-pressed', String(state.romaji));
    favBtn.setAttribute('aria-pressed', String(state.favOnly));
    clearBtn.setAttribute('aria-label', state.favOnly ? 'Effacer les favoris' : 'Effacer la conversation');
    ta.lang = state.speaker;
    sendBtn.classList.toggle('ja', state.speaker === 'ja');
    syncKeyboardLang();
    syncMic();
  }
  function autosize() {
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight + 2, 150) + 'px';
  }

  // ---------- speech output ----------
  const webTts = 'speechSynthesis' in window && typeof SpeechSynthesisUtterance !== 'undefined';
  const tts = !!NATIVE || webTts;
  let voices = [];
  function loadVoices() { try { voices = window.speechSynthesis.getVoices() || []; } catch (e) { voices = []; } }
  if (!NATIVE && webTts) { loadVoices(); try { window.speechSynthesis.addEventListener('voiceschanged', loadVoices); } catch (e) {} }
  function speak(text, lang) {
    if (!text) return;
    if (NATIVE) { try { NATIVE.speak(text, lang); } catch (e) {} return; }
    if (!webTts) return;
    try {
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text);
      u.lang = lang === 'ja' ? 'ja-JP' : 'fr-FR';
      const v = voices.find((x) => (x.lang || '').toLowerCase().replace('_', '-').startsWith(lang));
      if (v) u.voice = v;
      u.rate = lang === 'ja' ? 0.92 : 1;
      if (state.voice === 'male') u.pitch = 0.75;
      window.speechSynthesis.speak(u);
    } catch (e) { toast('Lecture audio indisponible ici.'); }
  }
  function stopSpeaking() {
    try { if (NATIVE) NATIVE.stopSpeaking(); else if (webTts) window.speechSynthesis.cancel(); } catch (e) {}
  }

  // ---------- speech input: one microphone key per language ----------
  // Both engines report through onSpeech: ready, partial {text}, final {text}, error {code}.
  const stt = (() => {
    try {
      if (NATIVE && NATIVE.canListen()) {
        return {
          start: (lang, silenceMs) => NATIVE.startListening(lang, silenceMs),
          stop: () => NATIVE.stopListening(),
          cancel: () => NATIVE.cancelListening()
        };
      }
    } catch (e) {}
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return null;
    let rec = null;
    return {
      start(lang, silenceMs) {
        const r = new SR();
        rec = r;
        let finals = '', interim = '', failed = false, timer = 0;
        // Keep listening through pauses; stop after silenceMs without new words.
        const arm = (ms) => {
          clearTimeout(timer);
          timer = setTimeout(() => { if (rec === r) r.stop(); }, ms);
          if (rec === r) onSpeech({ type: 'timer', ms });
        };
        r.lang = lang;
        r.interimResults = true;
        r.continuous = true;
        r.maxAlternatives = 1;
        r.onstart = () => { if (rec === r) { arm(Math.max(silenceMs, 6000)); onSpeech({ type: 'ready' }); } };
        r.onresult = (e) => {
          finals = ''; interim = '';
          for (let i = 0; i < e.results.length; i++) {
            if (e.results[i].isFinal) finals += e.results[i][0].transcript; else interim += e.results[i][0].transcript;
          }
          arm(silenceMs);
          if (rec === r) onSpeech({ type: 'partial', text: finals + interim });
        };
        r.onerror = (e) => {
          if (e.error === 'aborted' || rec !== r) return;
          if (e.error === 'no-speech' && (finals || interim)) return;
          failed = true;
          onSpeech({ type: 'error', code: e.error });
        };
        r.onend = () => {
          clearTimeout(timer);
          if (rec === r && !failed) onSpeech({ type: 'final', text: finals || interim });
          rec = rec === r ? null : rec;
        };
        r.start();
      },
      stop() { if (rec) rec.stop(); },
      cancel() { const r = rec; rec = null; if (r) r.abort(); }
    };
  })();

  const MIC_IDLE = { fr: 'Français', ja: '日本語' };
  const MIC_NAME = { fr: 'Parler français', ja: '日本語で話す' };
  const MIC_ON = { fr: 'J’écoute…', ja: 'どうぞ…' };
  const MIC_ENDING = { fr: 'Un instant…', ja: '少々…' };
  function syncMic() {
    micBtns.forEach((b) => {
      const lang = b.dataset.mic;
      const on = !!listening && listening.lang === lang;
      b.classList.toggle('listening', on);
      b.classList.toggle('ending', on && listening.ending);
      if (!on) drainRing(b, 0);
      b.setAttribute('aria-pressed', String(on));
      b.setAttribute('aria-label', on ? (lang === 'ja' ? '聞き取りを終える' : 'Arrêter l’écoute') : MIC_NAME[lang]);
      b.querySelector('.ico').innerHTML = on ? ICONS.stop : ICONS.mic;
      b.querySelector('.lbl').textContent = on ? (listening.ending ? MIC_ENDING : MIC_ON)[lang] : MIC_IDLE[lang];
    });
    ta.readOnly = !!listening;
    ta.placeholder = listening
      ? (listening.lang === 'ja' ? '話してください…' : 'Parle…')
      : (state.speaker === 'ja' ? (state.kana ? 'ローマ字で入力 → かな' : 'ここに入力') : 'Écris ici');
    // Rōmaji for kana: no suggestions nor autocorrect (the keyboard would turn "arigatou" into a
    // French word), so each letter arrives at once and becomes kana as it is typed.
    const plain = kanaTyping();
    ['autocomplete', 'autocorrect', 'autocapitalize'].forEach((a) => { if (plain) ta.setAttribute(a, 'off'); else ta.removeAttribute(a); });
    ta.spellcheck = !plain;
  }
  // In the Android app, the keyboard opens in the language of the key (Gboard follows the hint).
  let hintLang = '';
  function syncKeyboardLang() {
    if (!NATIVE || hintLang === state.speaker) return;
    hintLang = state.speaker;
    try { NATIVE.keyboard(state.speaker, false); } catch (e) {}
  }
  // Small keyboard key next to each microphone: type in that language.
  function openKeyboard(lang) {
    cancelListening();
    const changed = state.speaker !== lang;
    state.speaker = lang;
    if (NATIVE) hintLang = lang; // set below, together with opening the keyboard
    syncPrefs(); save();
    if (NATIVE) {
      ta.focus();
      try { NATIVE.keyboard(lang, true); } catch (e) {}
      return;
    }
    if (changed && document.activeElement === ta) ta.blur();
    setTimeout(() => ta.focus(), changed ? 80 : 0);
  }
  // Without speech recognition, point at the keyboard's own microphone key.
  function keyboardDictation(lang) {
    openKeyboard(lang);
    toast(lang === 'ja' ? 'キーボードのマイクをタップして話してください' : 'Touche le micro de ton clavier et parle');
  }
  // Second tap on the microphone, or the send key while listening: finish the sentence, it gets
  // translated. Further taps wait for that (they used to cancel the translation that followed).
  function finishListening() {
    if (!listening || listening.ending) return;
    listening.ending = true;
    logSpeech('page : fin demandée');
    syncMic();
    try { stt.stop(); } catch (e) {}
  }
  function toggleMic(lang) {
    if (listening) {
      if (listening.lang === lang) { finishListening(); return; }
      cancelListening();
    }
    if (!stt) { keyboardDictation(lang); return; }
    if (!getKey()) { syncBanner(); openSettings('Ajoute d’abord ta clé API Claude.'); return; }
    stopSpeaking();
    if (document.activeElement === ta) ta.blur();
    state.speaker = lang;
    listening = { lang, text: '', ending: false };
    logSpeech('—— dictée ' + (lang === 'ja' ? 'japonais' : 'français') + ', silence ' + state.silence + ' s');
    ta.value = '';
    autosize();
    syncPrefs(); save();
    try { stt.start(lang === 'ja' ? 'ja-JP' : 'fr-FR', Math.round(state.silence * 1000)); }
    catch (e) { listening = null; syncMic(); toast('La dictée n’a pas pu démarrer.'); }
  }
  function cancelListening() {
    if (!listening) return;
    listening = null;
    try { stt.cancel(); } catch (e) {}
    syncMic();
  }
  function micError(code, lang) {
    switch (code) {
      case 'no-speech':
      case 'no-match': return lang === 'ja' ? '聞き取れませんでした。もう一度どうぞ。' : 'Je n’ai rien entendu. Réessaie.';
      case 'not-allowed':
      case 'service-not-allowed': return 'Autorise le micro pour Kotoba (Réglages Android > Applis > Kotoba > Autorisations).';
      case 'network': return 'Dictée impossible : pas de réseau.';
      case 'language-not-supported': return 'Langue indisponible pour la dictée sur ce téléphone.';
      case 'audio-capture': return 'Micro indisponible : une autre appli l’utilise peut-être.';
      case 'busy': return 'Le micro est occupé. Réessaie dans un instant.';
      default: return 'La dictée a échoué. Réessaie.';
    }
  }
  // The outline of the listening key drains over the silence left (ms) before the dictation ends;
  // the engines send a new 'timer' each time they hear words. 0 stops it.
  function drainRing(b, ms) {
    const rect = b.querySelector('.ring rect');
    if (!rect) return;
    if (b.ringAnim) { b.ringAnim.cancel(); b.ringAnim = null; }
    if (ms > 0 && rect.animate) {
      b.ringAnim = rect.animate([{ strokeDashoffset: 0 }, { strokeDashoffset: 100 }], { duration: ms, easing: 'linear', fill: 'forwards' });
    }
  }
  // What the dictation did, for Settings > Dictée (the Android app adds its own lines).
  const speechLog = [];
  function logSpeech(line) {
    speechLog.push(line);
    if (speechLog.length > 150) speechLog.splice(0, speechLog.length - 150);
    const pre = $('#speechLog');
    if (pre && !settingsEl.hidden) pre.textContent = speechLog.join('\n');
  }
  function onSpeech(ev) {
    if (ev && ev.type === 'log') { logSpeech(ev.text || ''); return; }
    if (ev && (ev.type === 'final' || ev.type === 'error')) {
      logSpeech('page : ' + (ev.type === 'final' ? 'texte reçu (' + (ev.text || '').length + ' caractères)' : 'erreur ' + ev.code) + (listening ? '' : ', ignoré'));
    }
    if (!listening || !ev) return;
    if (ev.type === 'timer') {
      const b = micBtns.find((x) => x.dataset.mic === listening.lang);
      if (b && !listening.ending) drainRing(b, Number(ev.ms) || 0);
    } else if (ev.type === 'partial') {
      listening.text = ev.text || '';
      ta.value = listening.text;
      autosize();
    } else if (ev.type === 'final' || ev.type === 'error') {
      const lang = listening.lang;
      const text = ((ev.type === 'final' && ev.text) || '').trim();
      listening = null;
      syncMic();
      if (ev.type === 'error' || !text) {
        ta.value = '';
        autosize();
        toast(micError(ev.type === 'error' ? ev.code : 'no-speech', lang), 3200);
        return;
      }
      ta.value = text;
      autosize();
      if (busy) { queuedSrc = lang; toast('Traduction de la phrase précédente en cours…'); return; }
      submit(lang, true);
    }
  }
  // Called by the Android app: kotobaNative.speech({ type, text, code })
  // Keyboards that ignore the language hint (Samsung's): after the keyboard key, the app reports
  // the language the keyboard really shows; say once how to switch.
  const kbdTold = {};
  function onKeyboard(ev) {
    if (!ev || !ev.wanted) return;
    const shown = String(ev.actual || '').toLowerCase().replace('_', '-');
    logSpeech('clavier : voulu ' + ev.wanted + ', affiché ' + (shown || '?') + ' (' + (ev.ime || '?') + ')');
    if (shown.startsWith(ev.wanted) || kbdTold[ev.wanted] || document.activeElement !== ta) return;
    kbdTold[ev.wanted] = true;
    const samsung = /samsung|com\.sec\./i.test(ev.ime || '');
    if (ev.wanted === 'ja') {
      // Language unknown on another keyboard: only advise.
      if (!shown && !samsung) {
        toast('Le clavier n’est peut-être pas en japonais : touche 🌐, ou active « rōmaji → かな » dans Réglages > Clavier.', 7000);
        return;
      }
      // The keyboard stays in the alphabet: Kotoba writes the kana itself.
      if (!state.kana) {
        state.kana = true; save(); syncMic();
        try { if (NATIVE) NATIVE.keyboard('ja', true); } catch (e) {} // the keyboard reads the new settings
      }
      toast((samsung ? 'Le clavier Samsung reste en alphabet' : 'Le clavier reste en alphabet') + ' : tape le japonais en rōmaji, Kotoba l’écrit en かな (arigatou → ありがとう). Ou touche 🌐 pour son clavier japonais.', 8000);
      return;
    }
    toast((samsung ? 'Le clavier Samsung ne change pas de langue tout seul' : 'Le clavier n’est pas passé en français') + ' : touche 🌐 ou glisse la barre d’espace pour passer en français.', 7000);
  }

  // ---------- rōmaji → hiragana, for keyboards that stay in the alphabet ----------
  const KANA = (() => {
    const t = { a: 'あ', i: 'い', u: 'う', e: 'え', o: 'お' };
    const rows = { k: 'かきくけこ', s: 'さしすせそ', t: 'たちつてと', n: 'なにぬねの', h: 'はひふへほ', m: 'まみむめも',
      r: 'らりるれろ', l: 'らりるれろ', g: 'がぎぐげご', z: 'ざじずぜぞ', d: 'だぢづでど', b: 'ばびぶべぼ', p: 'ぱぴぷぺぽ' };
    for (const c in rows) 'aiueo'.split('').forEach((v, k) => { t[c + v] = rows[c][k]; });
    Object.assign(t, {
      ya: 'や', yu: 'ゆ', yo: 'よ', wa: 'わ', wo: 'を', wi: 'うぃ', we: 'うぇ', vu: 'ゔ',
      shi: 'し', chi: 'ち', tsu: 'つ', fu: 'ふ', ji: 'じ', fa: 'ふぁ', fi: 'ふぃ', fe: 'ふぇ', fo: 'ふぉ',
      she: 'しぇ', che: 'ちぇ', je: 'じぇ', ti: 'ち', tu: 'つ',
      xa: 'ぁ', xi: 'ぃ', xu: 'ぅ', xe: 'ぇ', xo: 'ぉ', xya: 'ゃ', xyu: 'ゅ', xyo: 'ょ', xtu: 'っ', xtsu: 'っ',
      '-': 'ー', ',': '、', '.': '。', '?': '？', '!': '！'
    });
    const ki = { k: 'き', g: 'ぎ', n: 'に', h: 'ひ', m: 'み', r: 'り', b: 'び', p: 'ぴ', s: 'し', z: 'じ', t: 'ち', c: 'ち', d: 'ぢ', j: 'じ' };
    const small = { a: 'ゃ', u: 'ゅ', o: 'ょ' };
    for (const c in ki) for (const v in small) t[c + 'y' + v] = ki[c] + small[v];
    for (const v in small) { t['sh' + v] = 'し' + small[v]; t['ch' + v] = 'ち' + small[v]; t['j' + v] = 'じ' + small[v]; }
    return t;
  })();
  const isVowel = (c) => !!c && 'aiueo'.includes(c);
  // final: the text is complete, so a last "n" becomes ん (while typing it may still become な…).
  function toKana(src, final) {
    const s = src.toLowerCase();
    let out = '', i = 0;
    while (i < s.length) {
      const c = s[i], n1 = s[i + 1] || '', n2 = s[i + 2] || '';
      if (c === 'n' && !isVowel(n1) && n1 !== 'y') {
        if (n1 === "'") { out += 'ん'; i += 2; continue; }
        if (n1 === 'n' && !isVowel(n2) && n2 !== 'y') { out += 'ん'; i += 2; continue; }
        if (!n1 && !final) { out += src[i]; i += 1; continue; }
        out += 'ん'; i += 1; continue;
      }
      if (c === n1 && 'bcdfghjklmpqrstvwxz'.includes(c)) { out += 'っ'; i += 1; continue; }
      if (c === 't' && n1 === 'c' && n2 === 'h') { out += 'っ'; i += 1; continue; }
      let len = 4;
      while (len > 0 && !KANA[s.substr(i, len)]) len--;
      if (len) { out += KANA[s.substr(i, len)]; i += len; } else { out += src[i]; i += 1; }
    }
    return out;
  }
  const kanaTyping = () => state.kana && state.speaker === 'ja' && !listening;
  // Convert as they type, unless the keyboard is still composing a word or the cursor is elsewhere.
  function liveKana() {
    if (!kanaTyping() || !/[a-z]/i.test(ta.value) || ta.selectionStart !== ta.value.length) return;
    const k = toKana(ta.value, false);
    if (k === ta.value) return;
    ta.value = k;
    ta.setSelectionRange(k.length, k.length);
    autosize();
  }
  window.kotobaNative = { speech: onSpeech, keyboard: onKeyboard };

  // ---------- actions ----------
  function addItem(it) {
    const reRender = !state.items.some(inConv) || state.favOnly;
    state.started = true;
    state.items.push(it);
    trimItems();
    if (state.favOnly) { state.favOnly = false; syncPrefs(); }
    if (reRender) render(); else { thread.append(buildCard(it)); toBottom(); }
  }
  // forcedSrc: the language of the microphone key, when the text was dictated.
  function submit(forcedSrc, spoken) {
    if (busy) return;
    const typed = ta.value.trim();
    const text = !spoken && kanaTyping() ? toKana(typed, true) : typed;
    if (!text) { ta.focus(); return; }
    if (!getKey()) { syncBanner(); openSettings('Ajoute d’abord ta clé API Claude.'); return; }
    const src = forcedSrc || detectSrc(text);
    const it = { id: newId(), kind: 'text', src, tgt: src === 'ja' ? 'fr' : 'ja', text, tr: '', ro: '', note: '', fav: false, ts: Date.now(), status: 'pending' };
    if (spoken) it.spoken = true;
    addItem(it);
    ta.value = '';
    autosize();
    run(it);
  }
  function copyItem(it, card) {
    const fallback = () => {
      const t = card.querySelector('.tr, .rows');
      if (!t) return;
      try {
        const r = document.createRange(); r.selectNodeContents(t);
        const s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
        toast('Texte sélectionné : copie-le avec le menu.');
      } catch (e) {}
    };
    if (NATIVE) {
      // Android 13+ shows its own "copied" notice.
      try { if (!NATIVE.copy(it.tr)) toast('Copié'); } catch (e) { fallback(); }
      return;
    }
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(it.tr).then(() => toast('Copié'), fallback);
      else fallback();
    } catch (e) { fallback(); }
  }
  let shown = null;
  function openShow(it) {
    shown = it;
    const tr = $('#showTr'), ro = $('#showRo');
    tr.textContent = it.tr; tr.lang = it.tgt;
    ro.textContent = it.ro || ''; ro.hidden = !it.ro;
    $('#showFrom').textContent = it.tgt === 'ja' ? 'フランス語から翻訳しました' : 'Traduit du japonais';
    const sp = $('#showSpeak');
    sp.hidden = !tts;
    sp.innerHTML = ICONS.speak;
    sp.append(el('span', null, it.tgt === 'ja' ? '読み上げる' : 'Écouter'));
    showEl.hidden = false;
    $('#closeShow').focus();
  }
  function closeShow() { showEl.hidden = true; shown = null; }

  thread.addEventListener('click', (e) => {
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const card = b.closest('.card');
    const it = card && state.items.find((i) => i.id === card.dataset.id);
    if (!it) return;
    const act = b.dataset.act;
    if (act === 'speak') speak(it.tr, it.tgt);
    else if (act === 'copy') copyItem(it, card);
    else if (act === 'show') openShow(it);
    else if (act === 'block') openSheet(it, Number(b.dataset.bi));
    else if (act === 'orig') {
      const st = card.querySelector('.stage');
      if (st) b.setAttribute('aria-pressed', String(st.classList.toggle('orig')));
    }
    else if (act === 'lens') openLens(it);
    else if (act === 'turn') turnPhoto(it);
    else if (act === 'view') { it.view = b.dataset.view; save(); updateCard(it); }
    else if (act === 'fav') {
      if (!it.fav) { it.fav = true; save(); updateCard(it); toast('Gardé dans les favoris'); return; }
      // removing a favourite asks first, in the card
      const prev = unfavAsk && unfavAsk !== it.id ? state.items.find((i) => i.id === unfavAsk) : null;
      unfavAsk = unfavAsk === it.id ? '' : it.id;
      if (prev) updateCard(prev);
      updateCard(it);
    } else if (act === 'unfav-no') {
      unfavAsk = '';
      updateCard(it);
    } else if (act === 'unfav-yes') {
      unfavAsk = '';
      it.fav = false;
      // already cleared from the conversation: nowhere left to show it
      if (it.out) { forgetPhoto(it.id); state.items.splice(state.items.indexOf(it), 1); }
      save();
      if (state.favOnly || it.out) render(); else updateCard(it);
      toast('Favori supprimé');
    } else if (act === 'retry') {
      if (busy) { toast('Une traduction est déjà en cours.'); return; }
      if (!getKey()) { openSettings('Ajoute d’abord ta clé API Claude.'); return; }
      run(it);
    }
  });

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    // Stop a translation only once its stop key has been visible a moment: a second tap meant to
    // send the dictated sentence used to cancel it.
    if (busy) { if (ctl && Date.now() - runStartedAt > 1000) ctl.abort(); return; }
    if (listening) { finishListening(); return; }
    submit();
  });
  // Tapping send keeps the keyboard open for the next sentence.
  sendBtn.addEventListener('pointerdown', (e) => { if (document.activeElement === ta) e.preventDefault(); });
  ta.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
      e.preventDefault();
      if (!busy && !listening) submit();
    }
  });
  ta.addEventListener('input', (e) => { autosize(); if (!e.isComposing) liveKana(); });
  ta.addEventListener('compositionend', () => setTimeout(liveKana, 0));

  micBtns.forEach((b) => b.addEventListener('click', () => toggleMic(b.dataset.mic)));
  kbdBtns.forEach((b) => {
    // Same language while typing: keep the keyboard open.
    b.addEventListener('pointerdown', (e) => {
      if (document.activeElement === ta && b.dataset.kbd === state.speaker) e.preventDefault();
    });
    b.addEventListener('click', () => openKeyboard(b.dataset.kbd));
  });
  document.querySelectorAll('[data-pref]').forEach((b) => b.addEventListener('click', () => {
    if (b.dataset.pref === 'tier') state.tier = b.dataset.val; else state.polite = b.dataset.val === '1';
    syncPrefs(); save();
  }));
  romajiBtn.addEventListener('click', () => { state.romaji = !state.romaji; syncPrefs(); save(); });
  favBtn.addEventListener('click', () => { state.favOnly = !state.favOnly; confirmBar.hidden = true; syncPrefs(); render(); });
  // The trash clears what is shown: the conversation, or the favourites. Always asks first.
  clearBtn.addEventListener('click', () => {
    if (!confirmBar.hidden) { confirmBar.hidden = true; return; }
    const favs = state.favOnly;
    if (favs ? !state.items.some((i) => i.fav) : !state.items.some(inConv)) {
      // only the examples on screen: they go, they are not messages
      if (!favs && !state.started) { state.started = true; save(); render(); toast('Exemples effacés'); return; }
      toast(favs ? 'Aucun favori à effacer.' : 'La conversation est déjà vide.');
      return;
    }
    confirmBar.dataset.what = favs ? 'favs' : 'conv';
    const nFav = state.items.filter((i) => i.fav && inConv(i)).length;
    $('#confirmText').textContent = favs ? 'Effacer tous les favoris ?'
      : 'Effacer la conversation ?' + (nFav ? (nFav > 1 ? ' Les ' + nFav + ' favoris restent.' : ' Le favori reste.') : '');
    confirmBar.hidden = false;
  });
  $('#cancelClear').addEventListener('click', () => { confirmBar.hidden = true; });
  // Favourites leave the conversation but stay in the favourites. The settings and the key stay.
  function clearConversation() {
    if (busy && ctl) ctl.abort();
    cancelListening();
    let kept = 0;
    state.items = state.items.filter((i) => {
      if (i.out) return true;
      if (i.fav) { i.out = true; kept++; return true; }
      forgetPhoto(i.id);
      return false;
    });
    ta.value = '';
    autosize();
    syncPrefs(); save(); render();
    toast(kept ? 'Conversation effacée, favoris gardés à part' : 'Conversation effacée');
  }
  // Favourites still in the conversation stay there, without the star.
  function clearFavourites() {
    state.items = state.items.filter((i) => {
      if (!i.fav) return true;
      if (i.out) { forgetPhoto(i.id); return false; }
      i.fav = false;
      return true;
    });
    state.favOnly = false;
    syncPrefs(); save(); render();
    toast('Favoris effacés');
  }
  $('#doClear').addEventListener('click', () => {
    confirmBar.hidden = true;
    if (confirmBar.dataset.what === 'favs') clearFavourites(); else clearConversation();
  });

  // Each label opens its own picker natively (more reliable in web views than a scripted click):
  // the camera input asks for the rear camera, the gallery input for an existing photo.
  photoPickers.forEach(([btn, input]) => {
    btn.addEventListener('click', (e) => {
      if (busy) { e.preventDefault(); toast('Attends la fin de la traduction en cours.'); return; }
      if (!getKey()) { e.preventDefault(); openSettings('Ajoute d’abord ta clé API Claude.'); return; }
      cancelListening();
      input.value = '';
    });
    btn.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); btn.click(); }
    });
    input.addEventListener('change', () => {
      const f = input.files && input.files[0];
      if (f) handlePhoto(f);
    });
  });
  function canvasFor(bmp, max) {
    const k = Math.min(1, max / Math.max(bmp.width, bmp.height));
    const cv = document.createElement('canvas');
    cv.width = Math.max(1, Math.round(bmp.width * k));
    cv.height = Math.max(1, Math.round(bmp.height * k));
    cv.getContext('2d').drawImage(bmp, 0, 0, cv.width, cv.height);
    return cv;
  }
  // Re-encode every photo as a reasonably sized JPEG: phone photos can be large or in formats Claude refuses.
  async function prepareImage(file) {
    let bmp;
    try { bmp = await createImageBitmap(file, { imageOrientation: 'from-image' }); } catch (e) {
      // very large photo: let the decoder shrink it
      try { bmp = await createImageBitmap(file, { imageOrientation: 'from-image', resizeWidth: 2048, resizeQuality: 'high' }); }
      catch (e2) { return { blob: file, thumb: '', photo: '', w: 0, h: 0 }; }
    }
    try {
      const big = canvasFor(bmp, 1568);
      const blob = await new Promise((res) => big.toBlob((b) => res(b), 'image/jpeg', 0.85));
      const photo = canvasFor(bmp, 1000).toDataURL('image/jpeg', 0.72);
      const thumb = canvasFor(bmp, 160).toDataURL('image/jpeg', 0.7);
      return { blob: blob || file, thumb, photo, w: big.width, h: big.height };
    } catch (e) { return { blob: file, thumb: '', photo: '', w: 0, h: 0 }; }
    finally { try { bmp.close(); } catch (e) {} }
  }
  async function handlePhoto(f) {
    if (busy) { toast('Attends la fin de la traduction en cours.'); return; }
    const it = { id: newId(), kind: 'image', src: 'ja', tgt: 'fr', text: '', tr: '', ro: '', note: '', fav: false, ts: Date.now(), status: 'pending', thumb: '', photo: '', w: 0, h: 0, title: '', blocks: [] };
    addItem(it);
    const img = await prepareImage(f);
    it.thumb = img.thumb;
    it.photo = img.photo;
    it.w = img.w; it.h = img.h;
    try { photoUrls.set(it.id, URL.createObjectURL(img.blob)); } catch (e) {}
    if (img.blob.size > 3.7 * 1024 * 1024) {
      it.status = 'error'; it.err = 'Photo trop lourde. Essaie une image plus petite.';
      updateCard(it); save();
      return;
    }
    files.set(it.id, img.blob);
    run(it);
  }

  $('#closeShow').addEventListener('click', closeShow);
  $('#showSpeak').addEventListener('click', () => { if (shown) speak(shown.tr, shown.tgt); });

  // ---------- settings ----------
  function setKeyStatus(msg, kind) {
    keyStatus.textContent = msg;
    keyStatus.className = 'key-status' + (kind ? ' ' + kind : '');
  }
  function renderUsage() {
    const u = loadUsage();
    const money = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: u.cost < 1 ? 3 : 2 }).format(u.cost);
    const since = new Date(u.since).toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' });
    $('#usageText').textContent = 'Environ ' + money + ' depuis le ' + since + ' (' + u.n + ' traduction' + (u.n > 1 ? 's' : '') + '). Estimation : le montant exact est sur platform.claude.com.';
  }
  function syncSilence() {
    silenceInput.value = String(state.silence);
    $('#silenceOut').textContent = String(state.silence).replace('.', ',') + ' s';
  }
  function syncVoice() {
    document.querySelectorAll('[data-voice]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.voice === state.voice)));
    $('#kanaInput').checked = state.kana;
    const info = $('#voiceInfo');
    let v = null;
    try { v = NATIVE ? JSON.parse(NATIVE.voices()) : null; } catch (e) {}
    if (!v || !v.fr) { info.textContent = NATIVE ? '' : 'Dans le navigateur, la voix d’homme est la voix habituelle rendue plus grave.'; return; }
    const missing = ['fr', 'ja'].filter((l) => !(v[l] && v[l][state.voice] > 0)).map((l) => (l === 'ja' ? 'japonaise' : 'française'));
    info.textContent = !missing.length
      ? 'Voix ' + (state.voice === 'male' ? 'd’homme' : 'de femme') + ' trouvée en français et en japonais.'
      : 'Pas de voix ' + (state.voice === 'male' ? 'd’homme' : 'de femme') + ' ' + missing.join(' ni ') + ' reconnue sur ce téléphone'
        + (state.voice === 'male' ? ' : Kotoba rend la voix habituelle plus grave. Pour une vraie voix d’homme, installe « Services de reconnaissance vocale de Google » et ses voix (Réglages Android > Synthèse vocale).' : '.');
    logSpeech('voix : ' + JSON.stringify(v));
  }
  function setVoice(g) {
    state.voice = g;
    save();
    try { if (NATIVE) NATIVE.voice(g); } catch (e) {}
    syncVoice();
  }
  function openSettings(msg) {
    cancelListening();
    syncSilence();
    syncVoice();
    $('#speechLog').textContent = speechLog.length ? speechLog.join('\n') : 'Rien pour l’instant : fais une dictée, puis reviens ici.';
    keyInput.value = storedKey();   // never shows the built-in key
    wsInput.value = storedWorkspace();
    keyInput.placeholder = BUILTIN.apiKey ? 'Clé fournie avec l’app' : 'sk-ant-…';
    keyInput.type = 'password';
    $('#keyShow').textContent = 'Afficher';
    if (msg) setKeyStatus(msg, 'bad');
    else if (keyProblem) setKeyStatus(copyFor(keyProblem), 'bad');
    else if (usingBuiltin()) setKeyStatus('Kotoba utilise la clé fournie avec l’app. Tu peux en coller une autre ci-dessus.', 'ok');
    else setKeyStatus(getKey() ? 'Clé enregistrée sur ce téléphone.' : '', getKey() ? 'ok' : '');
    renderUsage();
    settingsEl.hidden = false;
    if (!getKey()) setTimeout(() => keyInput.focus(), 60);
  }
  function closeSettings() { settingsEl.hidden = true; if (document.activeElement === keyInput) keyInput.blur(); }
  async function saveKey() {
    const k = keyInput.value.replace(/\s+/g, '');
    const w = wsInput.value.replace(/\s+/g, '');
    if (!k && !BUILTIN.apiKey) { setKeyStatus('Colle ta clé dans le champ.', 'bad'); return; }
    if (w && !/^wrkspc_[A-Za-z0-9]+$/.test(w)) { setKeyStatus('L’ID du workspace commence par wrkspc_ : copie-le en entier, ou laisse le champ vide.', 'bad'); return; }
    setKey(k);
    setWorkspace(w);
    keyInput.value = k;
    wsInput.value = w;
    keyProblem = '';
    syncBanner();
    const btn = $('#keySave');
    btn.disabled = true;
    setKeyStatus('Test de la clé…');
    const r = await testKey(getKey(), getWorkspace()); // empty field: the key built into the app
    btn.disabled = false;
    if (storedKey() !== k || storedWorkspace() !== w) return;
    if (!r.code) { setKeyStatus('Clé valide ✓ Tu peux traduire.', 'ok'); return; }
    if (KEY_CODES.has(r.code)) { keyProblem = r.code; syncBanner(); }
    if (r.code === 'bad_key') setKeyStatus('Clé refusée : vérifie que tu l’as copiée en entier.', 'bad');
    else if (r.code === 'needs_workspace') { setKeyStatus('Ta clé n’est liée à aucun workspace : colle l’ID du workspace ci-dessus (wrkspc_…), ou crée une clé liée au workspace Default.', 'bad'); wsInput.focus(); }
    else if (r.code === 'bad_workspace') setKeyStatus('Ce workspace est introuvable pour ta clé : vérifie son ID.', 'bad');
    else setKeyStatus('Clé enregistrée, mais le test a échoué : ' + (r.code === 'failed' && r.detail ? r.detail : copyFor(r.code)), 'bad');
  }
  setBtn.addEventListener('click', () => openSettings());
  $('#bannerBtn').addEventListener('click', () => openSettings());
  $('#closeSettings').addEventListener('click', closeSettings);
  $('#keySave').addEventListener('click', saveKey);
  [keyInput, wsInput].forEach((i) => i.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); saveKey(); } }));
  $('#keyShow').addEventListener('click', () => {
    const hide = keyInput.type === 'text';
    keyInput.type = hide ? 'password' : 'text';
    $('#keyShow').textContent = hide ? 'Afficher' : 'Masquer';
  });
  $('#keyDelete').addEventListener('click', () => {
    setKey('');
    setWorkspace('');
    keyInput.value = '';
    wsInput.value = '';
    keyProblem = '';
    syncBanner();
    setKeyStatus(BUILTIN.apiKey ? 'Clé supprimée : Kotoba reprend la clé fournie avec l’app.' : 'Clé supprimée de ce téléphone.');
  });
  $('#copyLog').addEventListener('click', () => {
    const text = speechLog.join('\n');
    if (!text) return;
    try {
      if (NATIVE) { if (!NATIVE.copy(text)) toast('Journal copié'); }
      else navigator.clipboard.writeText(text).then(() => toast('Journal copié'));
    } catch (e) {}
  });
  document.querySelectorAll('[data-voice]').forEach((b) => b.addEventListener('click', () => setVoice(b.dataset.voice)));
  $('#tryFr').addEventListener('click', () => speak('Bonjour, je suis la voix de Kotoba. Tu m’entends bien ?', 'fr'));
  $('#tryJa').addEventListener('click', () => speak('こんにちは。ことばの声です。よろしくお願いします。', 'ja'));
  $('#kanaInput').addEventListener('change', () => { state.kana = $('#kanaInput').checked; save(); syncMic(); });
  silenceInput.addEventListener('input', () => {
    const v = Number(silenceInput.value);
    if (v >= 1 && v <= 15) { state.silence = v; syncSilence(); save(); }
  });
  $('#usageReset').addEventListener('click', () => {
    try { localStorage.removeItem(KEY_USAGE); } catch (e) {}
    renderUsage();
  });
  try { $('#version').textContent = 'Kotoba ' + (NATIVE ? NATIVE.version() : 'web'); } catch (e) {}

  // ---------- one block in detail ----------
  let sheetBlock = null;
  function openSheet(it, i) {
    const b = it.blocks && it.blocks[i];
    if (!b) return;
    sheetBlock = b;
    $('#sheetO').textContent = b.o || '';
    $('#sheetO').hidden = !b.o;
    $('#sheetT').textContent = b.t;
    $('#sheetSpeak').hidden = !tts || !b.o;
    sheet.hidden = false;
  }
  function closeSheet() { sheet.hidden = true; sheetBlock = null; }
  $('#sheetClose').addEventListener('click', closeSheet);
  $('#sheetSpeak').addEventListener('click', () => { if (sheetBlock) speak(sheetBlock.o, 'ja'); });

  // ---------- full-screen photo with zoom ----------
  const ZOOMS = [1, 1.5, 2, 3, 4];
  let lvItem = null, lvZoom = 1;
  function layoutLens() {
    if (!lvItem) return;
    const r = shownRatio(lvItem);
    const base = Math.min(lvScroll.clientWidth, lvScroll.clientHeight * r);
    lvWrap.style.width = Math.max(1, Math.round(base * lvZoom)) + 'px';
    $('#lvLevel').textContent = '×' + lvZoom;
  }
  function setZoom(z) {
    const sc = lvScroll;
    const cx = (sc.scrollLeft + sc.clientWidth / 2) / Math.max(1, sc.scrollWidth);
    const cy = (sc.scrollTop + sc.clientHeight / 2) / Math.max(1, sc.scrollHeight);
    lvZoom = z;
    layoutLens();
    sc.scrollLeft = cx * sc.scrollWidth - sc.clientWidth / 2;
    sc.scrollTop = cy * sc.scrollHeight - sc.clientHeight / 2;
  }
  function openLens(it) {
    const src = photoSrc(it);
    if (!src) return;
    lvItem = it; lvZoom = 1;
    $('#lvTitle').textContent = it.title || 'Photo traduite';
    lvOrig.setAttribute('aria-pressed', 'false');
    lvWrap.textContent = '';
    lvWrap.append(buildStage(it, src, true));
    lensView.hidden = false;
    darkBars(true);
    layoutLens();
    lvScroll.scrollTop = 0; lvScroll.scrollLeft = 0;
    $('#lvClose').focus();
  }
  // A quarter turn clockwise, on screen: in the thread and in the full-screen view.
  function turnPhoto(it) {
    it.turn = (turnOf(it) + 270) % 360;
    save();
    updateCard(it);
    if (lvItem === it) {
      const src = photoSrc(it);
      lvOrig.setAttribute('aria-pressed', 'false');
      lvWrap.textContent = '';
      if (src) lvWrap.append(buildStage(it, src, true));
      layoutLens();
    }
  }
  $('#lvTurn').addEventListener('click', () => { if (lvItem) turnPhoto(lvItem); });
  function closeLens() { closeSheet(); lensView.hidden = true; lvItem = null; lvWrap.textContent = ''; darkBars(false); }
  $('#lvClose').addEventListener('click', closeLens);
  $('#lvIn').addEventListener('click', () => { const k = ZOOMS.indexOf(lvZoom); if (k < ZOOMS.length - 1) setZoom(ZOOMS[k + 1]); });
  $('#lvOut').addEventListener('click', () => { const k = ZOOMS.indexOf(lvZoom); if (k > 0) setZoom(ZOOMS[k - 1]); });
  lvOrig.addEventListener('click', () => {
    const st = lvWrap.querySelector('.stage');
    if (st) lvOrig.setAttribute('aria-pressed', String(st.classList.toggle('orig')));
  });
  lvWrap.addEventListener('click', (e) => {
    const ov = e.target.closest('.ov');
    if (ov && lvItem) openSheet(lvItem, Number(ov.dataset.bi));
  });
  window.addEventListener('resize', () => { if (lvItem) layoutLens(); });

  // ---------- Android window ----------
  // The page draws under the status and navigation bars; the app tells it how tall they are (CSS px).
  function setInsets(top, bottom) {
    const root = document.documentElement.style;
    root.setProperty('--inset-top', (Number(top) || 0) + 'px');
    root.setProperty('--inset-bottom', (Number(bottom) || 0) + 'px');
  }
  // Light status bar icons over the dark photo viewer.
  function darkBars(on) { try { if (NATIVE) NATIVE.darkBars(on); } catch (e) {} }
  window.kotobaInsets = setInsets;
  if (NATIVE) { try { const v = JSON.parse(NATIVE.insets()); setInsets(v[0], v[1]); } catch (e) {} }

  // Android back button (MainActivity) and Escape: close the top layer; false lets the app go to the background.
  function back() {
    if (!sheet.hidden) closeSheet();
    else if (!lensView.hidden) closeLens();
    else if (!showEl.hidden) closeShow();
    else if (!settingsEl.hidden) closeSettings();
    else if (listening) cancelListening();
    else if (!confirmBar.hidden) confirmBar.hidden = true;
    else return false;
    return true;
  }
  window.kotobaBack = back;
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') back(); });
  document.addEventListener('visibilitychange', () => { if (document.hidden) { cancelListening(); stopSpeaking(); } });

  // ---------- start ----------
  load();
  try { if (NATIVE) NATIVE.voice(state.voice); } catch (e) {}
  setSending(false);
  syncPrefs();
  syncBanner();
  render();
})();
