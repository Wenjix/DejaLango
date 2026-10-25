(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const token = params.get('token');
  let socket, state, retry;
  if (window.top !== window || params.has('embedded')) document.documentElement.classList.add('embedded');
  const text = (id, value) => { $(id).textContent = value || ''; };
  function render(s) {
    $('lens').hidden = s.paused;
    if (s.paused) return;
    const c = s.capture;
    let title = !s.listening ? 'Listening paused' : 'Listening for them';
    if (c.phase === 'ARMED') title = 'Ready · go ahead';
    else if (c.mine) title = c.phase === 'CLOSING' ? 'Closing… silence ends your turn' : c.phase === 'FINISHING' ? 'Finishing your last word…' : 'You’re talking';
    else if (c.closed !== null) title = '✓ sent · listening for them';
    else if (s.processing) title = 'Finding your words…';
    text('status', (s.synthetic ? 'Synthetic · ' : '') + title);
    const r = s.correction;
    text('receipt', r ? (r.pending ? 'Checking the split…' : r.text ? 'Moved to ' + (r.to === 'THEM' ? 'them' : 'you') + ': “' + r.text + '” · U Undo' + (r.whole ? ' · whole line on phone' : '') : r.message + ' · U Undo') : '');
    text('receiptRoman', r && !r.pending && r.text ? r.roman : '');
    text('heard', s.heard && s.heard.text);
    text('heardRoman', s.heard && s.heard.roman);
    text('meaning', s.heard && s.heard.translation);
    const hint = s.hint;
    text('word', hint.phase === 'PENDING' ? '…' : hint.phase === 'READY' ? hint.word : '');
    text('wordRoman', hint.phase === 'READY' ? hint.roman : '');
    text('wordMeaning', hint.phase === 'READY' ? hint.meaning : '');
    const index = s.selected == null ? 0 : s.selected;
    const reply = s.replies[index];
    text('position', reply ? (s.kind === 'VARIANTS' ? 'Say it' : s.source === 'PLAYBOOK' ? 'Prepared' : 'Reply') + ' · ' + (index + 1) + '/' + s.replies.length : '');
    text('replyText', reply ? reply.yours : s.wearer ? 'You: ' + s.wearer.text : c.mine ? 'Capturing your turn' : '');
    text('roman', reply && s.pair.roman ? reply.roman : !reply && s.wearer ? s.wearer.roman : '');
    text('back', reply ? reply.back : '');
    text('error', s.error || s.pendingPair && 'Language pair changes next turn' || '');
  }
  function action(action, extra = {}) {
    if (socket && socket.readyState === WebSocket.OPEN && state) socket.send(JSON.stringify({action, session:state.session, ...extra}));
  }
  function connect() {
    if (!token) return;
    text('connection', 'Connecting to your phone…');
    socket = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws?token=' + encodeURIComponent(token));
    socket.onmessage = event => {
      try {
        state = JSON.parse(event.data); $('connection').hidden = true; render(state);
        if (window.parent !== window) window.parent.postMessage({type:'companion-state', state}, location.origin);
      } catch (_) { text('connection', 'Display update unavailable. Reopen your HUD link.'); }
    };
    socket.onclose = () => { $('connection').hidden = false; text('connection', 'Connection ended. Reopen the current HUD link.'); clearTimeout(retry); retry = setTimeout(connect, 2000); };
    socket.onerror = () => socket.close();
  }
  window.addEventListener('message', event => {
    if (event.origin === location.origin && event.source === window.parent && event.data && event.data.type === 'companion-action') action(event.data.action, event.data.extra);
  });
  document.addEventListener('keydown', event => {
    if (event.repeat) return;
    const key = event.key.toLowerCase();
    const mapped = {t:'talk', c:'done', p:'pause', arrowright:'next', arrowleft:'prev', arrowup:'talk'};
    if (mapped[key]) { action(mapped[key]); event.preventDefault(); }
    else if (key === 'u' && state && state.correction) action('undo', {correctionId:state.correction.id});
    else if ((key === 's' || key === 'f') && state && state.latestLineId !== null) action('flip', {lineId:state.latestLineId});
  });
  connect();
})();
