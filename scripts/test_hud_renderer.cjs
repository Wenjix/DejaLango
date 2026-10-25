'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const fixtures = JSON.parse(fs.readFileSync(path.join(root, 'app/build/hud-wire-fixtures.json'), 'utf8'));
const nodes = new Map();
const node = id => {
  if (!nodes.has(id)) nodes.set(id, { textContent:'', hidden:false, classList:{add(){}} });
  return nodes.get(id);
};
let socket;
const sent = [], events = {};
class Socket {
  static OPEN = 1;
  constructor() { socket = this; this.readyState = 1; }
  send(value) { sent.push(JSON.parse(value)); }
}
const window = { addEventListener() {} }; window.top = window; window.parent = window;
const context = {
  window, document:{ getElementById:node, documentElement:{classList:{add(){}}}, addEventListener(name, callback) { events[name] = callback; } },
  URLSearchParams, location:{search:'?token=synthetic-test',protocol:'http:',host:'localhost',origin:'http://localhost'},
  WebSocket:Socket, setTimeout, clearTimeout,
};
vm.runInNewContext(fs.readFileSync(path.join(root, 'app/src/main/assets/hud/hud.js'), 'utf8'), context);
const show = state => socket.onmessage({data:JSON.stringify(state)});
const render = name => show(fixtures[name]);
render('split');
assert.match(node('receipt').textContent, /Moved to them: “要加糖嗎？”/);
assert.match(node('receipt').textContent, /U Undo/);
assert.match(node('receipt').textContent, /whole line on phone/);
assert.equal(node('receiptRoman').textContent, 'Yào jiā táng ma?');
events.keydown({key:'s', repeat:false, preventDefault(){}});
assert.deepEqual(sent.at(-1), {action:'flip', session:'renderer-test', lineId:2});
events.keydown({key:'u', repeat:false, preventDefault(){}});
assert.deepEqual(sent.at(-1), {action:'undo', session:'renderer-test', correctionId:1});
render('unavailable');
assert.match(node('receipt').textContent, /audio has expired/);
assert.doesNotMatch(node('receipt').textContent, /Moved to/);
assert.equal(node('receiptRoman').textContent, '');
render('whole');
assert.match(node('receipt').textContent, /一杯咖啡。要加糖嗎？/);
assert.doesNotMatch(node('receipt').textContent, /whole line on phone/);
render('undo');
assert.equal(node('receipt').textContent, '');
assert.equal(node('receiptRoman').textContent, '');
// Cards answer the heard line; a state without "source", from an older phone, is the model's.
const replies = [{yours:'不用，謝謝。', roman:'Bú yòng, xièxie.', back:'No, thanks.'}, {yours:'要一點。', roman:'Yào yìdiǎn.', back:'A little.'}];
for (const [extra, label] of [[{source:undefined}, 'Reply'], [{source:'MODEL'}, 'Reply'], [{source:'PLAYBOOK'}, 'Prepared'], [{source:'PLAYBOOK', kind:'VARIANTS'}, 'Say it']]) {
  show({...fixtures.undo, replies, ...extra});
  assert.equal(node('position').textContent, label + ' · 1/2');
  assert.equal(node('replyText').textContent, '不用，謝謝。');
  assert.equal(node('other').hidden, false);
  assert.equal(node('otherText').textContent, '要一點。');
}
// Browsing to card 2 moves card 1 beneath it.
show({...fixtures.undo, replies, source:'PLAYBOOK', selected:1});
assert.equal(node('position').textContent, 'Prepared · 2/2');
assert.equal(node('replyText').textContent, '要一點。');
assert.equal(node('otherText').textContent, '不用，謝謝。');
// One card leaves the second slot hidden.
show({...fixtures.undo, replies:[replies[0]]});
assert.equal(node('other').hidden, true);
assert.equal(node('otherText').textContent, '');
render('paused');
assert.equal(node('lens').hidden, true);
console.log('PASS: actual HudJson split, unavailable, whole-line, Undo and display-pause payloads; model, playbook and variant card labels; both cards shown');
