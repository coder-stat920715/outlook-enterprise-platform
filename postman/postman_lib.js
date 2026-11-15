const v = k => pm.variables.get(k);
const set = (k, val) => pm.collectionVariables.set(k, val);
const b64url = s => CryptoJS.enc.Base64.stringify(CryptoJS.enc.Utf8.parse(s)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
// Unsigned JWT-shaped token accepted by the mock IdP (introspection mode). exp is stable within an hour so the gateway SWR cache gets hits.
const mint = (sub, tid, mid, region, extra, expOverride) => {
  const exp = expOverride || (Math.floor(Date.now() / 3600000) + 2) * 3600;
  const payload = Object.assign({ sub: sub, tid: tid, mid: mid, region: region, exp: exp }, extra || {});
  return b64url(JSON.stringify({ alg: 'none', typ: 'JWT' })) + '.' + b64url(JSON.stringify(payload)) + '.sig';
};
const wait = ms => new Promise(r => setTimeout(r, ms));
const send = opts => new Promise((res, rej) => pm.sendRequest(opts, (e, r) => e ? rej(e) : res(r)));
const call = (method, path, token, body, headers) => send({
  url: v('baseUrl') + path, method: method,
  header: Object.assign({ 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' }, headers || {}),
  body: body ? { mode: 'raw', raw: JSON.stringify(body) } : undefined
});
// Java UUID.nameUUIDFromBytes(utf8(str)) == MD5 with version-3 / IETF variant bits
const uuid3 = str => {
  const b = CryptoJS.MD5(CryptoJS.enc.Utf8.parse(str)).toString().match(/../g).map(h => parseInt(h, 16));
  b[6] = (b[6] & 0x0f) | 0x30; b[8] = (b[8] & 0x3f) | 0x80;
  const h = b.map(x => x.toString(16).padStart(2, '0')).join('');
  return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
};
return { v, set, b64url, mint, wait, send, call, uuid3 };
