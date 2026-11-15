#!/usr/bin/env python3
"""Generates Enterprise-Outlook.postman_collection.json + environment (Postman v2.1). Run: python3 build_collection.py"""
import json, os, uuid

HERE = os.path.dirname(os.path.abspath(__file__))
LIB = open(os.path.join(HERE, "postman_lib.js")).read()
LOAD = "const L = new Function('pm','CryptoJS', pm.collectionVariables.get('lib'))(pm, CryptoJS);"
T1, T2 = "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"

def ev(kind, lines, needs_lib=True):
    body = ([LOAD] if needs_lib else []) + lines
    return {"listen": kind, "script": {"type": "text/javascript", "exec": body}}

def item(name, method, path, token="tokenAlice", body=None, headers=None, test=None, pre=None, desc="", url=None, content_type="application/json", raw_text=None):
    hdrs = []
    if body is not None or raw_text is not None:
        hdrs.append({"key": "Content-Type", "value": content_type})
    for k, val in (headers or {}).items():
        hdrs.append({"key": k, "value": val})
    req = {"method": method, "header": hdrs, "url": url or ("{{baseUrl}}" + path), "description": desc}
    if token:
        req["auth"] = {"type": "bearer", "bearer": [{"key": "token", "value": "{{%s}}" % token, "type": "string"}]}
    else:
        req["auth"] = {"type": "noauth"}
    if body is not None:
        req["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2), "options": {"raw": {"language": "json"}}}
    if raw_text is not None:
        req["body"] = {"mode": "raw", "raw": raw_text, "options": {"raw": {"language": "text"}}}
    events = []
    if pre: events.append(ev("prerequest", pre))
    if test: events.append(ev("test", test))
    return {"name": name, "event": events, "request": req}

def folder(name, desc, items):
    return {"name": name, "description": desc, "item": items}

def st(code, label=None):
    return "pm.test('%s', () => pm.response.to.have.status(%d));" % (label or ("Status is %d" % code), code)

WAIT = lambda ms: ["await L.wait(%d);" % ms]
J = "const j = pm.response.json();"

def mail(to, subject, body="Automated test message", **extra):
    d = {"to": [{"email": to[0], "name": to[1]}], "cc": [], "bcc": [], "subject": subject, "body": body, "bodyType": "text", "importance": 1, "attachmentIds": []}
    d.update(extra); return d

ALICE, BOB, SHARED = ("alice@corp.test", "Alice Anderson"), ("bob@corp.test", "Bob Builder"), ("support@corp.test", "Support Desk")

# ---------------------------------------------------------------------------------------------------------------- folders
F = []

F.append(folder("00 Edge, infra & auth guard", "Verifies the stack is up and that the gateway rejects anonymous traffic. Run FIRST.", [
    item("0.1 Edge health (NGINX)", "GET", "", token=None, url="{{baseUrl}}/lb-health",
         desc="Step: send. Expect 200 body 'ok' – NGINX edge is alive.",
         test=[st(200), "pm.test('body ok', () => pm.expect(pm.response.text()).to.include('ok'));"]),
    item("0.2 Envoy cluster view (healthy gateways)", "GET", "", token=None, url="{{envoyAdminUrl}}/clusters",
         desc="Envoy admin. Expect both gateway hosts listed and health_flags::healthy. If a host shows /failed_active_hc it is still starting (wait ~30s).",
         test=[st(200), "const t = pm.response.text();",
               "pm.test('gateways cluster present', () => pm.expect(t).to.include('gateways::'));",
               "pm.test('two gateway endpoints', () => pm.expect((t.match(/gateways::[0-9.]+:8080::cx_active/g)||[]).length).to.be.at.least(2));"]),
    item("0.3 Reset mock IdP (latency 0, healthy)", "POST", "", token=None, url="{{idpUrl}}/admin/state",
         body={"latency_ms": 0, "fail": False}, desc="Ensures a clean IdP before the run. Expect 200 and the state echoed back.",
         test=[st(200), J, "pm.test('idp healthy', () => { pm.expect(j.latency_ms).to.eql(0); pm.expect(j.fail).to.eql(false); });"]),
    item("0.4 Reset all region gates to OPEN", "PUT", "/gateway-admin/regions/APAC", token=None, headers={"X-Admin-Key": "{{adminKey}}"},
         body={"state": "OPEN"}, desc="Guarantees regions are open before testing. Expect 200.",
         pre=["['EMEA','AMER'].forEach(r => L.send({url: L.v('baseUrl') + '/gateway-admin/regions/' + r, method: 'PUT', header: {'X-Admin-Key': L.v('adminKey'), 'Content-Type':'application/json'}, body: {mode:'raw', raw: JSON.stringify({state:'OPEN'})}}));"],
         test=[st(200)]),
    item("0.5 No token -> 401", "GET", "/v1/mailboxes/00000000-0000-0000-0000-000000000000/folders", token=None,
         desc="Anonymous request must be rejected at the gateway.", test=[st(401)]),
]))

F.append(folder("01 Provisioning", "Creates tenant-1 users alice, bob, shared mailbox support, carol (APAC) and a tenant-2 user dave. Variables aliceId/bobId/sharedId/carolId/daveId are saved automatically.", [
    item("1.1 Provision Alice", "POST", "/v1/admin/mailboxes", token="tokenAdmin",
         body={"email": ALICE[0], "displayName": ALICE[1], "mailboxType": "USER", "region": "AMER"},
         desc="Creates directory entry + mailbox + 5 well-known folders on the mailbox's shard. Idempotent by email.",
         pre=["L.set('runId', String(Date.now()));"],
         test=[st(200), J, "pm.test('mailboxId returned', () => pm.expect(j.mailboxId).to.match(/^[0-9a-f-]{36}$/));", "L.set('aliceId', j.mailboxId);"]),
    item("1.2 Provision Bob (display name 'Bob Builder')", "POST", "/v1/admin/mailboxes", token="tokenAdmin",
         body={"email": BOB[0], "displayName": BOB[1], "mailboxType": "USER", "region": "AMER"},
         test=[st(200), J, "L.set('bobId', j.mailboxId);"]),
    item("1.3 Provision shared mailbox (support@)", "POST", "/v1/admin/mailboxes", token="tokenAdmin",
         body={"email": SHARED[0], "displayName": SHARED[1], "mailboxType": "SHARED", "region": "AMER"},
         test=[st(200), J, "L.set('sharedId', j.mailboxId);"]),
    item("1.4 Provision Carol (APAC)", "POST", "/v1/admin/mailboxes", token="tokenAdmin",
         body={"email": "carol@corp.test", "displayName": "Carol Chen", "mailboxType": "USER", "region": "APAC"},
         test=[st(200), J, "L.set('carolId', j.mailboxId);"]),
    item("1.5 Re-provision Alice (idempotent)", "POST", "/v1/admin/mailboxes", token="tokenAdmin",
         body={"email": ALICE[0], "displayName": ALICE[1], "mailboxType": "USER", "region": "AMER"},
         desc="Same email must return the SAME mailboxId.",
         test=[st(200), J, "pm.test('same mailboxId', () => pm.expect(j.mailboxId).to.eql(L.v('aliceId')));"]),
    item("1.6 Provision Dave in tenant 2", "POST", "/v1/admin/mailboxes", token="tokenTenant2",
         body={"email": "dave@other.test", "displayName": "Dave Other", "mailboxType": "USER", "region": "AMER"},
         desc="Different tenant – used later for isolation and cross-tenant free/busy tests.",
         test=[st(200), J, "L.set('daveId', j.mailboxId);"]),
]))

F.append(folder("02 Folders & tenant isolation", "Folder listing (counts come from the counter table), custom folders, tenant isolation and spoofed-header protection.", [
    item("2.1 Alice folders", "GET", "/v1/mailboxes/{{aliceId}}/folders",
         desc="Expect INBOX, SENT, DRAFTS, ARCHIVE, DELETED. IDs are saved.",
         test=[st(200), J, "pm.test('5 well-known folders', () => pm.expect(j.filter(f => f.wellKnown).length).to.be.at.least(5));",
               "const by = n => j.find(f => f.wellKnown === n).folderId;",
               "L.set('aliceInboxId', by('INBOX')); L.set('aliceSentId', by('SENT')); L.set('aliceArchiveId', by('ARCHIVE')); L.set('aliceDeletedId', by('DELETED'));"]),
    item("2.2 Bob folders", "GET", "/v1/mailboxes/{{bobId}}/folders", token="tokenBob",
         test=[st(200), J, "const by = n => j.find(f => f.wellKnown === n).folderId;",
               "L.set('bobInboxId', by('INBOX')); L.set('bobSentId', by('SENT')); L.set('bobArchiveId', by('ARCHIVE'));"]),
    item("2.3 Create custom folder 'Projects'", "POST", "/v1/mailboxes/{{aliceId}}/folders", body={"displayName": "Projects"},
         test=[st(200), J, "pm.test('folderId', () => pm.expect(j.folderId).to.be.a('string'));", "L.set('aliceProjectsId', j.folderId);"]),
    item("2.4 Tenant isolation: tenant-2 user reads Alice's mailbox -> 403", "GET", "/v1/mailboxes/{{aliceId}}/folders", token="tokenTenant2",
         desc="Tenant-2 principal targets a tenant-1 mailbox id. No delegation exists in tenant 2 => 403.", test=[st(403)]),
    item("2.5 Spoofed X-Tenant-ID is ignored", "GET", "/v1/mailboxes/{{aliceId}}/folders", headers={"X-Tenant-ID": T2, "X-User-Mailbox-ID": "{{bobId}}"},
         desc="Client-supplied identity headers are stripped/overwritten by the gateway from the validated token => still 200 as Alice/tenant-1.",
         test=[st(200), J, "pm.test('Alice folders returned', () => pm.expect(j.some(f => f.wellKnown === 'INBOX')).to.be.true);"]),
]))

F.append(folder("03 Send, receive, threads, pagination", "End-to-end Kafka pipeline: send -> outbound -> inbound -> read; idempotency, counters, reply threading, keyset pagination.", [
    item("3.1 Alice -> Bob: send (202, QUEUED)", "POST", "/v1/mailboxes/{{aliceId}}/messages/send", headers={"Idempotency-Key": "{{runId}}-1"},
         body=mail(BOB, "Hello quasar{{runId}}", "Quarterly planning notes."),
         desc="202 means the message is durably in Kafka (acks=all) – not yet delivered.",
         test=[st(202), J, "pm.test('QUEUED', () => pm.expect(j.state).to.eql('QUEUED'));", "L.set('sentMessageId', j.messageId); L.set('convId', j.conversationId);"]),
    item("3.2 Replay same Idempotency-Key -> 200 replay", "POST", "/v1/mailboxes/{{aliceId}}/messages/send", headers={"Idempotency-Key": "{{runId}}-1"},
         body=mail(BOB, "Hello quasar{{runId}}", "Quarterly planning notes."),
         test=[st(200), J, "pm.test('replayed header', () => pm.expect(pm.response.headers.get('Idempotent-Replayed')).to.eql('true'));",
               "pm.test('same messageId', () => pm.expect(j.messageId).to.eql(L.v('sentMessageId')));"]),
    item("3.3 Validation: no recipients -> 422", "POST", "/v1/mailboxes/{{aliceId}}/messages/send",
         body={"to": [], "subject": "x", "body": "y"}, test=[st(422)]),
    item("3.4 Bob inbox shows the message", "GET", "/v1/mailboxes/{{bobId}}/folders/{{bobInboxId}}/messages?limit=50", token="tokenBob",
         desc="Waits 3s for async delivery. Expect the new message unread.", pre=WAIT(3000),
         test=[st(200), J, "const m = j.items.find(x => x.subject === 'Hello quasar' + L.v('runId'));",
               "pm.test('message delivered to Bob', () => pm.expect(m).to.exist);",
               "pm.test('unread, no attachment', () => { pm.expect(m.isRead).to.be.false; pm.expect(m.hasAttachments).to.be.false; });",
               "pm.test('sender is Alice', () => pm.expect(m.senderEmail).to.eql('alice@corp.test'));", "L.set('bobMessageId', m.messageId);"]),
    item("3.5 Bob reads message detail", "GET", "/v1/mailboxes/{{bobId}}/messages/{{bobMessageId}}", token="tokenBob",
         test=[st(200), J, "pm.test('body present', () => pm.expect(j.bodyContent).to.include('Quarterly'));",
               "pm.test('delivery state DELIVERED', () => pm.expect(j.deliveryState).to.eql('DELIVERED'));", "L.set('bobMsgVersion', j.version);"]),
    item("3.6 Alice Sent folder contains the message", "GET", "/v1/mailboxes/{{aliceId}}/folders/{{aliceSentId}}/messages?limit=50",
         test=[st(200), J, "pm.test('sent copy exists', () => pm.expect(j.items.some(x => x.messageId === L.v('sentMessageId'))).to.be.true);"]),
    item("3.7 Alice sent copy reached DELIVERED", "GET", "/v1/mailboxes/{{aliceId}}/messages/{{sentMessageId}}",
         desc="Sent copy transitions SENDING -> DELIVERED after fan-out.",
         test=[st(200), J, "pm.test('state', () => pm.expect(['SENDING','DELIVERED']).to.include(j.deliveryState));"]),
    item("3.8 Bob folder counters (before read)", "GET", "/v1/mailboxes/{{bobId}}/folders", token="tokenBob",
         test=[st(200), J, "const inbox = j.find(f => f.wellKnown === 'INBOX');", "pm.test('unread >= 1', () => pm.expect(inbox.unreadCount).to.be.at.least(1));", "L.set('bobUnreadBefore', inbox.unreadCount);"]),
    item("3.9 Bob marks message read (PATCH)", "PATCH", "/v1/mailboxes/{{bobId}}/messages/{{bobMessageId}}", token="tokenBob", body={"isRead": True},
         desc="Optimistic version check + counter update + delta log row in one transaction.",
         test=[st(200), J, "pm.test('isRead', () => pm.expect(j.summary.isRead).to.be.true);",
               "pm.test('version incremented', () => pm.expect(j.version).to.eql(Number(L.v('bobMsgVersion')) + 1));"]),
    item("3.10 Bob unread counter decreased by 1", "GET", "/v1/mailboxes/{{bobId}}/folders", token="tokenBob",
         test=[st(200), J, "const inbox = j.find(f => f.wellKnown === 'INBOX');",
               "pm.test('unread decreased', () => pm.expect(inbox.unreadCount).to.eql(Number(L.v('bobUnreadBefore')) - 1));"]),
    item("3.11 Bob replies (threading)", "POST", "/v1/mailboxes/{{bobId}}/messages/send", token="tokenBob",
         body=mail(ALICE, "Re: Hello quasar{{runId}}", "Looks good.", inReplyToMessageId="{{bobMessageId}}"),
         desc="Reply copies the parent's conversation id.",
         test=[st(202), J, "pm.test('same conversation as original', () => pm.expect(j.conversationId).to.eql(L.v('convId')));"]),
    item("3.12 Alice thread view has original + reply", "GET", "/v1/mailboxes/{{aliceId}}/messages/{{sentMessageId}}/thread",
         desc="Waits 3s for the reply to land in Alice's inbox.", pre=WAIT(3000),
         test=[st(200), J, "pm.test('>= 2 messages in thread', () => pm.expect(j.length).to.be.at.least(2));",
               "pm.test('all same conversation', () => pm.expect(new Set(j.map(m => m.conversationId)).size).to.eql(1));"]),
    item("3.13 Bob moves message to Archive", "PATCH", "/v1/mailboxes/{{bobId}}/messages/{{bobMessageId}}", token="tokenBob", body={"folderId": "{{bobArchiveId}}"},
         desc="Counters of both folders are adjusted; delta change type MOVED.",
         test=[st(200), J, "pm.test('moved', () => pm.expect(j.folderId).to.eql(L.v('bobArchiveId')));"]),
    item("3.14 Bulk-send 4 more mails (script)", "POST", "/v1/mailboxes/{{aliceId}}/messages/send", body=mail(BOB, "Page test 5 quasar{{runId}}", "page"),
         desc="Pre-request script sends 4 extra messages so pagination has >= 5 rows.",
         pre=["for (let i = 1; i <= 4; i++) { await L.call('POST', '/v1/mailboxes/' + L.v('aliceId') + '/messages/send', L.v('tokenAlice'), {to:[{email:'bob@corp.test',name:'Bob'}], subject:'Page test ' + i + ' quasar' + L.v('runId'), body:'page', bodyType:'text'}); }"],
         test=[st(202)]),
    item("3.15 Pagination page 1 (limit=2)", "GET", "/v1/mailboxes/{{bobId}}/folders/{{bobInboxId}}/messages?limit=2", token="tokenBob", pre=WAIT(4000),
         desc="Keyset pagination: nextCursor encodes (received_at, message_id).",
         test=[st(200), J, "pm.test('2 items', () => pm.expect(j.items.length).to.eql(2));", "pm.test('has nextCursor', () => pm.expect(j.nextCursor).to.be.a('string'));",
               "L.set('page1Ids', JSON.stringify(j.items.map(i => i.messageId))); L.set('page1LastAt', j.items[1].receivedAt); L.set('cursor1', j.nextCursor);"]),
    item("3.16 Pagination page 2 (disjoint from page 1)", "GET", "/v1/mailboxes/{{bobId}}/folders/{{bobInboxId}}/messages?limit=2&cursor={{cursor1}}", token="tokenBob",
         test=[st(200), J, "const p1 = JSON.parse(L.v('page1Ids'));", "pm.test('no overlap', () => pm.expect(j.items.some(i => p1.includes(i.messageId))).to.be.false);",
               "pm.test('older than page 1', () => pm.expect(new Date(j.items[0].receivedAt) <= new Date(L.v('page1LastAt'))).to.be.true);"]),
    item("3.17 unreadOnly=true returns only unread", "GET", "/v1/mailboxes/{{bobId}}/folders/{{bobInboxId}}/messages?unreadOnly=true&limit=50", token="tokenBob",
         test=[st(200), J, "pm.test('all unread', () => pm.expect(j.items.every(i => i.isRead === false)).to.be.true);"]),
]))

F.append(folder("04 Delta sync", "Incremental sync with opaque tokens: initial snapshot, changes, no-change fast path, tamper detection, paging, expiry (410).", [
    item("4.1 Initial delta (no token) -> fullSyncRequired", "GET", "/v1/mailboxes/{{aliceId}}/messages/delta",
         test=[st(200), J, "pm.test('fullSyncRequired', () => pm.expect(j.fullSyncRequired).to.be.true);", "pm.test('token returned', () => pm.expect(j.nextDeltaToken).to.be.a('string'));", "L.set('deltaToken0', j.nextDeltaToken);"]),
    item("4.2 Alice sends one more mail", "POST", "/v1/mailboxes/{{aliceId}}/messages/send", body=mail(BOB, "Delta probe quasar{{runId}}"), test=[st(202)]),
    item("4.3 Delta with token0 -> new changes", "GET", "/v1/mailboxes/{{aliceId}}/messages/delta?deltaToken={{deltaToken0}}", pre=WAIT(3500),
         test=[st(200), J, "pm.test('changes present', () => pm.expect(j.changes.length).to.be.at.least(1));",
               "pm.test('change shape', () => { const c = j.changes[0]; pm.expect(c).to.have.keys(['changeSeq','entityType','entityId','changeType','folderId']); });",
               "pm.test('seq ascending', () => pm.expect(j.changes.every((c, i, a) => i === 0 || a[i-1].changeSeq < c.changeSeq)).to.be.true);",
               "L.set('deltaToken1', j.nextDeltaToken); L.set('deltaCount', j.changes.length);"]),
    item("4.4 Delta with token1 -> nothing new", "GET", "/v1/mailboxes/{{aliceId}}/messages/delta?deltaToken={{deltaToken1}}",
         desc="Hits the Redis watermark fast path (no PostgreSQL). Token should come back unchanged.",
         test=[st(200), J, "pm.test('no changes', () => pm.expect(j.changes.length).to.eql(0));", "pm.test('token stable', () => pm.expect(j.nextDeltaToken).to.eql(L.v('deltaToken1')));"]),
    item("4.5 Tampered token -> 400", "GET", "/v1/mailboxes/{{aliceId}}/messages/delta?deltaToken=MTIzLmRlYWRiZWVm", test=[st(400)]),
    item("4.6 Token bound to mailbox: Alice's token on Bob's mailbox -> 400", "GET", "/v1/mailboxes/{{bobId}}/messages/delta?deltaToken={{deltaToken1}}", token="tokenBob",
         desc="The MAC binds the token to (tenant, mailbox).", test=[st(400)]),
    item("4.7 Paging with limit=1", "GET", "/v1/mailboxes/{{aliceId}}/messages/delta?deltaToken={{deltaToken0}}&limit=1",
         test=[st(200), J, "pm.test('1 change per page', () => pm.expect(j.changes.length).to.eql(1));", "pm.test('hasMore when >1 total', () => pm.expect(j.hasMore).to.eql(Number(L.v('deltaCount')) > 1));"]),
    item("4.8 [MANUAL] Expired token -> 410", "GET", "/v1/mailboxes/{{aliceId}}/messages/delta?deltaToken={{deltaToken0}}",
         desc="See TESTING_GUIDE step 4.8: first run `UPDATE mailbox_sync_state SET min_retained_seq = last_seq + 10` on Alice's shard, then send this. Without the SQL step the request returns 200 and the test is skipped.",
         test=["if (pm.response.code === 410) pm.test('410 Gone => client must full-resync', () => pm.expect(pm.response.code).to.eql(410)); else console.log('SQL step not executed – skipped');"]),
]))

F.append(folder("05 Attachments", "Presigned PUT upload (PUT-only storage ingress :9000) -> complete -> signed CDN download (NGINX secure_link + cache) -> cross-mailbox access.", [
    item("5.1 Request upload URL", "POST", "/v1/mailboxes/{{aliceId}}/attachments/upload-url",
         body={"fileName": "report.txt", "contentType": "text/plain", "sizeBytes": 23, "inline": False},
         test=[st(200), J, "pm.test('presigned PUT', () => { pm.expect(j.method).to.eql('PUT'); pm.expect(j.uploadUrl).to.include('X-Amz-Signature'); });",
               "L.set('attId', j.attachmentId); L.set('uploadUrl', j.uploadUrl);"]),
    item("5.2 Upload bytes directly to storage ingress", "PUT", "", token=None, url="{{uploadUrl}}", raw_text="Hello attachment world!", content_type="text/plain",
         desc="Goes to NGINX :9000 -> MinIO. Never passes through the API. Expect 200.", test=[st(200)]),
    item("5.3 Complete upload", "POST", "/v1/mailboxes/{{aliceId}}/attachments/{{attId}}/complete", test=[st(204)]),
    item("5.4 Get signed CDN download URL", "GET", "/v1/mailboxes/{{aliceId}}/attachments/{{attId}}/download-url",
         test=[st(200), J, "pm.test('CDN signed url', () => { pm.expect(j.url).to.include('/cdn/attachments/'); pm.expect(j.url).to.include('md5='); pm.expect(j.url).to.include('expires='); });", "L.set('downloadUrl', j.url);"]),
    item("5.5 Download via CDN (first: MISS)", "GET", "", token=None, url="{{downloadUrl}}",
         test=[st(200), "pm.test('content intact', () => pm.expect(pm.response.text()).to.eql('Hello attachment world!'));",
               "pm.test('cache header present', () => pm.expect(pm.response.headers.get('X-Cache-Status')).to.be.a('string'));"]),
    item("5.6 Download again (cache HIT)", "GET", "", token=None, url="{{downloadUrl}}",
         test=[st(200), "pm.test('served from CDN cache', () => pm.expect(pm.response.headers.get('X-Cache-Status')).to.eql('HIT'));"]),
    item("5.7 Tampered signature -> 403", "GET", "", token=None, url="{{downloadUrl}}0",
         desc="Appending a char to the `expires` value breaks the secure_link hash.", test=[st(403)]),
    item("5.8 Send mail with the attachment", "POST", "/v1/mailboxes/{{aliceId}}/messages/send", body=mail(BOB, "With attachment quasar{{runId}}", "see file", attachmentIds=["{{attId}}"]),
         test=[st(202)]),
    item("5.9 Bob sees hasAttachments=true", "GET", "/v1/mailboxes/{{bobId}}/folders/{{bobInboxId}}/messages?limit=50", token="tokenBob", pre=WAIT(3500),
         test=[st(200), J, "const m = j.items.find(x => x.subject === 'With attachment quasar' + L.v('runId'));", "pm.test('delivered with attachment flag', () => { pm.expect(m).to.exist; pm.expect(m.hasAttachments).to.be.true; });"]),
    item("5.10 Bob's download URL for the same object (no blob copy)", "GET", "", token="tokenBob", url="{{baseUrl}}/v1/mailboxes/{{bobId}}/attachments/{{bobAttId}}/download-url",
         desc="Recipient attachment id = UUIDv3(attachmentId|bobMailboxId), computed in the pre-request script exactly like the server.",
         pre=["L.set('bobAttId', L.uuid3(L.v('attId') + '|' + L.v('bobId')));"],
         test=[st(200), J, "pm.test('url returned', () => pm.expect(j.url).to.include('/cdn/attachments/'));"]),
]))

F.append(folder("06 Search", "OpenSearch full-text with mandatory tenant/mailbox filters.", [
    item("6.1 Bob searches his mail", "GET", "/v1/mailboxes/{{bobId}}/search?q=quasar{{runId}}", token="tokenBob", pre=WAIT(5000),
         desc="Indexing is asynchronous (search.indexer topic) – eventual consistency, normally < 5s.",
         test=[st(200), J, "pm.test('hits found', () => pm.expect(j.length).to.be.at.least(1));", "pm.test('hit shape', () => pm.expect(j[0]).to.have.keys(['messageId','subject','sender','snippet','receivedAt']));"]),
    item("6.2 Carol searching the same term sees nothing (isolation)", "GET", "/v1/mailboxes/{{carolId}}/search?q=quasar{{runId}}", token="tokenCarol",
         test=[st(200), J, "pm.test('no leakage', () => pm.expect(j.length).to.eql(0));"]),
    item("6.3 Bob searching Alice's mailbox -> 403", "GET", "/v1/mailboxes/{{aliceId}}/search?q=quasar", token="tokenBob", test=[st(403)]),
]))

F.append(folder("07 Inbox rules & Out-of-Office", "Rule: subject contains INVOICE -> Archive + read + category. OOF auto-reply with once-per-sender dedupe and loop protection.", [
    item("7.1 Bob creates rule", "POST", "/v1/mailboxes/{{bobId}}/rules", token="tokenBob",
         body={"name": "Invoices", "priority": 1, "enabled": True, "condition": {"subjectContains": "INVOICE"},
               "action": {"moveToFolderId": "{{bobArchiveId}}", "markRead": True, "category": "Finance", "stopProcessing": True}},
         test=[st(200), J, "pm.test('ruleId', () => pm.expect(j.ruleId).to.be.a('string'));"]),
    item("7.2 Alice sends INVOICE mail", "POST", "/v1/mailboxes/{{aliceId}}/messages/send", body=mail(BOB, "INVOICE {{runId}}", "pay me"), test=[st(202)]),
    item("7.3 Mail landed in Bob's Archive, already read", "GET", "/v1/mailboxes/{{bobId}}/folders/{{bobArchiveId}}/messages?limit=50", token="tokenBob", pre=WAIT(3500),
         test=[st(200), J, "const m = j.items.find(x => x.subject === 'INVOICE ' + L.v('runId'));", "pm.test('rule moved + marked read', () => { pm.expect(m).to.exist; pm.expect(m.isRead).to.be.true; });"]),
    item("7.4 INVOICE mail is NOT in Bob's Inbox", "GET", "/v1/mailboxes/{{bobId}}/folders/{{bobInboxId}}/messages?limit=100", token="tokenBob",
         test=[st(200), J, "pm.test('not in inbox', () => pm.expect(j.items.some(x => x.subject === 'INVOICE ' + L.v('runId'))).to.be.false);"]),
    item("7.5 Bob enables Out-of-Office", "PUT", "/v1/mailboxes/{{bobId}}/settings/oof", token="tokenBob",
         body={"enabled": True, "subject": "Out of Office", "replyText": "I am away until Monday."}, desc="Also resets the per-sender dedupe set.", test=[st(204)]),
    item("7.6 Alice sends a normal mail", "POST", "/v1/mailboxes/{{aliceId}}/messages/send", body=mail(BOB, "Question {{runId}}", "ping"), test=[st(202)]),
    item("7.7 Alice receives exactly one auto-reply", "GET", "/v1/mailboxes/{{aliceId}}/folders/{{aliceInboxId}}/messages?limit=100", pre=WAIT(6000),
         test=[st(200), J, "const ar = j.items.filter(x => x.subject === 'Out of Office: Question ' + L.v('runId'));", "pm.test('one auto reply', () => pm.expect(ar.length).to.eql(1));", "L.set('oofCount', ar.length);"]),
    item("7.8 Alice sends again", "POST", "/v1/mailboxes/{{aliceId}}/messages/send", body=mail(BOB, "Question2 {{runId}}", "ping again"), test=[st(202)]),
    item("7.9 Dedupe: no second auto-reply", "GET", "/v1/mailboxes/{{aliceId}}/folders/{{aliceInboxId}}/messages?limit=100", pre=WAIT(6000),
         test=[st(200), J, "const ar = j.items.filter(x => x.subject.startsWith('Out of Office:') && x.subject.includes(L.v('runId')));", "pm.test('still exactly one', () => pm.expect(ar.length).to.eql(1));"]),
    item("7.10 Bob disables OOF", "PUT", "/v1/mailboxes/{{bobId}}/settings/oof", token="tokenBob", body={"enabled": False, "replyText": ""}, test=[st(204)]),
]))

F.append(folder("08 Shared mailbox & delegation (RBAC)", "SendAs / FullAccess. NOTE: permission sets are cached 30s per mail pod; 8.7 waits 32s.", [
    item("8.1 Shared mailbox grants Alice SEND_AS", "PUT", "/v1/mailboxes/{{sharedId}}/delegates", token="tokenShared", body={"granteeMailboxId": "{{aliceId}}", "permission": "SEND_AS"}, test=[st(204)]),
    item("8.2 Alice sends AS support@", "POST", "/v1/mailboxes/{{sharedId}}/messages/send", body=mail(BOB, "From support quasar{{runId}}", "hello from the desk"),
         desc="Actor = Alice (token), mailbox = shared. SEND_AS makes the From address support@corp.test.", pre=WAIT(1000), test=[st(202)]),
    item("8.3 Bob sees sender support@corp.test", "GET", "/v1/mailboxes/{{bobId}}/folders/{{bobInboxId}}/messages?limit=100", token="tokenBob", pre=WAIT(3500),
         test=[st(200), J, "const m = j.items.find(x => x.subject === 'From support quasar' + L.v('runId'));", "pm.test('From = shared mailbox', () => { pm.expect(m).to.exist; pm.expect(m.senderEmail).to.eql('support@corp.test'); });"]),
    item("8.4 Bob (no rights) cannot send as support@ -> 403", "POST", "/v1/mailboxes/{{sharedId}}/messages/send", token="tokenBob", body=mail(ALICE, "illegal"), test=[st(403)]),
    item("8.5 Bob cannot read shared mailbox -> 403", "GET", "/v1/mailboxes/{{sharedId}}/folders", token="tokenBob", test=[st(403)]),
    item("8.6 SEND_AS alone does not grant read -> 403", "GET", "/v1/mailboxes/{{sharedId}}/folders", token="tokenAlice", test=[st(403)]),
    item("8.7 Grant Alice FULL_ACCESS", "PUT", "/v1/mailboxes/{{sharedId}}/delegates", token="tokenShared", body={"granteeMailboxId": "{{aliceId}}", "permission": "FULL_ACCESS"}, test=[st(204)]),
    item("8.8 Alice can now read the shared mailbox (after 32s cache TTL)", "GET", "/v1/mailboxes/{{sharedId}}/folders", token="tokenAlice", pre=WAIT(32000),
         desc="Per-pod permission cache is 30s, so wait before asserting.", test=[st(200)]),
    item("8.9 Revoke FULL_ACCESS", "DELETE", "/v1/mailboxes/{{sharedId}}/delegates/{{aliceId}}/FULL_ACCESS", token="tokenShared", test=[st(204)]),
]))

F.append(folder("09 Contacts, GAL & tasks", "Personal contacts, Global Address List (prefix then trigram fuzzy) and flag->task projection.", [
    item("9.1 Alice adds contact", "POST", "/v1/mailboxes/{{aliceId}}/contacts", body={"displayName": "Zed Zimmerman", "email": "zed@partner.test", "phone": "+1-555-0100", "company": "Partner Inc"},
         test=[st(200), J, "pm.test('contactId', () => pm.expect(j.contactId).to.be.a('string'));"]),
    item("9.2 Contact prefix search", "GET", "/v1/mailboxes/{{aliceId}}/contacts?prefix=Zed", test=[st(200), J, "pm.test('found', () => pm.expect(j.some(c => c.email === 'zed@partner.test')).to.be.true);"]),
    item("9.3 GAL prefix 'Ali'", "GET", "/v1/directory/gal?q=Ali", test=[st(200), J, "pm.test('alice in GAL', () => pm.expect(j.some(e => e.email === 'alice@corp.test')).to.be.true);"]),
    item("9.4 GAL fuzzy 'Bob Bilder' (typo)", "GET", "/v1/directory/gal?q=Bob%20Bilder", desc="No prefix match -> pg_trgm similarity fallback finds 'Bob Builder'.",
         test=[st(200), J, "pm.test('fuzzy match', () => pm.expect(j.some(e => e.email === 'bob@corp.test')).to.be.true);"]),
    item("9.5 GAL is tenant-scoped", "GET", "/v1/directory/gal?q=Ali", token="tokenTenant2", test=[st(200), J, "pm.test('tenant-2 cannot see tenant-1 people', () => pm.expect(j.length).to.eql(0));"]),
    item("9.6 Bob flags a message", "PATCH", "/v1/mailboxes/{{bobId}}/messages/{{bobMessageId}}", token="tokenBob", body={"isFlagged": True}, test=[st(200)]),
    item("9.7 Flag projected as To-Do task", "GET", "/v1/mailboxes/{{bobId}}/tasks", token="tokenBob",
         test=[st(200), J, "const t = j.find(x => x.sourceMessageId === L.v('bobMessageId'));", "pm.test('task exists', () => { pm.expect(t).to.exist; pm.expect(t.title).to.include('Follow up'); pm.expect(t.status).to.eql('OPEN'); });"]),
    item("9.8 Un-flag completes the task", "PATCH", "/v1/mailboxes/{{bobId}}/messages/{{bobMessageId}}", token="tokenBob", body={"isFlagged": False}, test=[st(200)]),
    item("9.9 Task no longer open", "GET", "/v1/mailboxes/{{bobId}}/tasks", token="tokenBob",
         test=[st(200), J, "pm.test('closed', () => pm.expect(j.some(x => x.sourceMessageId === L.v('bobMessageId'))).to.be.false);"]),
]))

F.append(folder("10 Calendar", "Events, validation, DST-correct recurrence, invites, RSVP, optimistic locking, cancellation, free/busy (incl. cross-tenant policy).", [
    item("10.1 Alice creates a single event", "POST", "/v1/mailboxes/{{aliceId}}/events",
         body={"title": "Design review", "location": "Room 1", "startAt": "2026-11-20T15:00:00Z", "endAt": "2026-11-20T16:00:00Z", "timeZone": "America/New_York", "allDay": False, "sendInvites": False},
         test=[st(201), J, "pm.test('DRAFT (no attendees)', () => pm.expect(j.meetingStatus).to.eql('DRAFT'));", "L.set('eventSingleId', j.eventId);"]),
    item("10.2 Window query returns it", "GET", "/v1/mailboxes/{{aliceId}}/events?from=2026-11-20T00:00:00Z&to=2026-11-21T00:00:00Z",
         test=[st(200), J, "pm.test('occurrence present', () => pm.expect(j.some(o => o.eventId === L.v('eventSingleId') && o.recurring === false)).to.be.true);"]),
    item("10.3 Invalid time zone -> 422", "POST", "/v1/mailboxes/{{aliceId}}/events", body={"title": "x", "startAt": "2026-11-20T15:00:00Z", "endAt": "2026-11-20T16:00:00Z", "timeZone": "Mars/Phobos"}, test=[st(422)]),
    item("10.4 End before start -> 422", "POST", "/v1/mailboxes/{{aliceId}}/events", body={"title": "x", "startAt": "2026-11-20T16:00:00Z", "endAt": "2026-11-20T15:00:00Z", "timeZone": "UTC"}, test=[st(422)]),
    item("10.5 Invalid RRULE -> 422", "POST", "/v1/mailboxes/{{aliceId}}/events", body={"title": "x", "startAt": "2026-11-20T15:00:00Z", "endAt": "2026-11-20T16:00:00Z", "timeZone": "UTC", "rrule": "FREQ=SOMETIMES"}, test=[st(422)]),
    item("10.6 Weekly meeting 09:00 New York with Bob (invites sent)", "POST", "/v1/mailboxes/{{aliceId}}/events",
         body={"title": "DST Weekly Sync", "startAt": "2026-10-26T13:00:00Z", "endAt": "2026-10-26T14:00:00Z", "timeZone": "America/New_York", "rrule": "FREQ=WEEKLY;BYDAY=MO",
               "attendees": [{"email": "bob@corp.test", "name": "Bob Builder"}], "sendInvites": True},
         desc="2026-10-26 09:00 EDT = 13:00Z. DST ends Sun 1 Nov 2026.",
         test=[st(201), J, "pm.test('SENT', () => pm.expect(j.meetingStatus).to.eql('SENT'));", "pm.test('Bob NEEDS_ACTION', () => pm.expect(j.attendees[0].response).to.eql('NEEDS_ACTION'));", "L.set('recurId', j.eventId);"]),
    item("10.7 DST check: 09:00 local stays 09:00 across 1 Nov", "GET", "/v1/mailboxes/{{aliceId}}/events?from=2026-10-26T00:00:00Z&to=2026-11-10T00:00:00Z",
         test=[st(200), J, "const s = j.filter(o => o.eventId === L.v('recurId')).map(o => o.start);",
               "pm.test('3 occurrences', () => pm.expect(s.length).to.eql(3));",
               "pm.test('before DST end 13:00Z', () => pm.expect(s[0]).to.eql('2026-10-26T13:00:00Z'));",
               "pm.test('after DST end 14:00Z', () => { pm.expect(s[1]).to.eql('2026-11-02T14:00:00Z'); pm.expect(s[2]).to.eql('2026-11-09T14:00:00Z'); });"]),
    item("10.8 Bob received a tentative copy", "GET", "/v1/mailboxes/{{bobId}}/events?from=2026-10-26T00:00:00Z&to=2026-11-10T00:00:00Z", token="tokenBob", pre=WAIT(3000),
         test=[st(200), J, "pm.test('copies exist', () => pm.expect(j.length).to.be.at.least(1));", "pm.test('TENTATIVE', () => pm.expect(j[0].showAs).to.eql('TENTATIVE'));", "L.set('bobEventId', j[0].eventId);"]),
    item("10.9 Invitation e-mail with iCalendar arrived", "GET", "/v1/mailboxes/{{bobId}}/folders/{{bobInboxId}}/messages?limit=100", token="tokenBob",
         test=[st(200), J, "pm.test('Invitation: DST Weekly Sync', () => pm.expect(j.items.some(m => m.subject === 'Invitation: DST Weekly Sync')).to.be.true);"]),
    item("10.10 Bob accepts", "POST", "/v1/mailboxes/{{bobId}}/events/{{bobEventId}}/respond", token="tokenBob", body={"response": "ACCEPTED"}, test=[st(204)]),
    item("10.11 Organizer sees Bob ACCEPTED", "GET", "/v1/mailboxes/{{aliceId}}/events/{{recurId}}",
         test=[st(200), J, "pm.test('RSVP propagated', () => pm.expect(j.attendees.find(a => a.email === 'bob@corp.test').response).to.eql('ACCEPTED'));", "L.set('recurVersion', j.version);"]),
    item("10.12 Bob's copy now BUSY", "GET", "/v1/mailboxes/{{bobId}}/events?from=2026-10-26T00:00:00Z&to=2026-11-10T00:00:00Z", token="tokenBob",
         test=[st(200), J, "pm.test('BUSY', () => pm.expect(j[0].showAs).to.eql('BUSY'));"]),
    item("10.13 Stale version update -> 409", "PUT", "/v1/mailboxes/{{aliceId}}/events/{{recurId}}", body={"title": "stale write", "version": 0},
         desc="Optimistic locking: RSVP already bumped the version.", test=[st(409)]),
    item("10.14 Update with current version -> UPDATED, SEQUENCE+1", "PUT", "/v1/mailboxes/{{aliceId}}/events/{{recurId}}", body={"title": "DST Weekly Sync (moved)", "version": "{{recurVersion}}"},
         test=[st(200), J, "pm.test('UPDATED', () => pm.expect(j.meetingStatus).to.eql('UPDATED'));", "pm.test('sequence bumped', () => pm.expect(j.sequenceNo).to.eql(1));"]),
    item("10.15 Bob cannot mutate Alice's calendar -> 403", "POST", "/v1/mailboxes/{{aliceId}}/events", token="tokenBob",
         body={"title": "x", "startAt": "2026-11-20T15:00:00Z", "endAt": "2026-11-20T16:00:00Z", "timeZone": "UTC"}, test=[st(403)]),
    item("10.16 Alice cancels the series", "POST", "/v1/mailboxes/{{aliceId}}/events/{{recurId}}/cancel", test=[st(204)]),
    item("10.17 Cancelled series disappears from Alice window", "GET", "/v1/mailboxes/{{aliceId}}/events?from=2026-10-26T00:00:00Z&to=2026-11-10T00:00:00Z",
         test=[st(200), J, "pm.test('gone', () => pm.expect(j.some(o => o.eventId === L.v('recurId'))).to.be.false);"]),
    item("10.18 ...and from Bob's window (cancel distributed)", "GET", "/v1/mailboxes/{{bobId}}/events?from=2026-10-26T00:00:00Z&to=2026-11-10T00:00:00Z", token="tokenBob", pre=WAIT(2500),
         test=[st(200), J, "pm.test('gone', () => pm.expect(j.some(o => o.eventId === L.v('bobEventId'))).to.be.false);"]),
    item("10.19 Bob creates own event 15:30-16:30Z (for free/busy)", "POST", "/v1/mailboxes/{{bobId}}/events", token="tokenBob",
         body={"title": "Bob focus", "startAt": "2026-11-20T15:30:00Z", "endAt": "2026-11-20T16:30:00Z", "timeZone": "UTC"}, test=[st(201)]),
    item("10.20 Free/busy for Alice + Bob", "POST", "/v1/freebusy",
         body={"mailboxes": [{"mailboxId": "{{aliceId}}"}, {"mailboxId": "{{bobId}}"}], "start": "2026-11-20T14:00:00Z", "end": "2026-11-20T18:00:00Z", "durationMinutes": 60},
         desc="Alice busy 15:00-16:00, Bob 15:30-16:30 => union 15:00-16:30; common free slots 14:00-15:00 and 16:30-18:00.",
         test=[st(200), J, "pm.test('busy for both', () => pm.expect(Object.keys(j.busy).length).to.eql(2));",
               "pm.test('first free slot 14:00-15:00', () => { pm.expect(j.commonFreeSlots[0].start).to.eql('2026-11-20T14:00:00Z'); pm.expect(j.commonFreeSlots[0].end).to.eql('2026-11-20T15:00:00Z'); });",
               "pm.test('second free slot 16:30-18:00', () => pm.expect(j.commonFreeSlots[1].start).to.eql('2026-11-20T16:30:00Z'));"]),
    item("10.21 Cross-tenant free/busy blocked by policy -> 403", "POST", "/v1/freebusy",
         body={"mailboxes": [{"tenantId": T2, "mailboxId": "{{daveId}}"}], "start": "2026-11-20T14:00:00Z", "end": "2026-11-20T18:00:00Z", "durationMinutes": 30}, test=[st(403)]),
    item("10.22 Window > 62 days -> 422", "POST", "/v1/freebusy", body={"mailboxes": [{"mailboxId": "{{aliceId}}"}], "start": "2026-01-01T00:00:00Z", "end": "2026-12-31T00:00:00Z", "durationMinutes": 30}, test=[st(422)]),
]))

F.append(folder("11 Room booking (no double-booking)", "Redisson lock + version CAS + PostgreSQL exclusion constraint.", [
    item("11.1 Create room", "POST", "/v1/resources", body={"name": "Everest", "kind": "ROOM", "capacity": 12, "location": "Floor 3"},
         test=[st(200), J, "L.set('roomId', j.resourceId);"]),
    item("11.2 Book 10:00-11:00", "POST", "/v1/resources/{{roomId}}/book", body={"startAt": "2026-11-20T10:00:00Z", "endAt": "2026-11-20T11:00:00Z", "organizerEmail": "alice@corp.test", "title": "Standup"},
         test=[st(201), J, "L.set('booking1', j.bookingId);"]),
    item("11.3 Overlapping 10:30-11:30 -> 409", "POST", "/v1/resources/{{roomId}}/book", body={"startAt": "2026-11-20T10:30:00Z", "endAt": "2026-11-20T11:30:00Z", "organizerEmail": "bob@corp.test"}, test=[st(409)]),
    item("11.4 Adjacent 11:00-12:00 -> 201 (half-open ranges)", "POST", "/v1/resources/{{roomId}}/book", body={"startAt": "2026-11-20T11:00:00Z", "endAt": "2026-11-20T12:00:00Z", "organizerEmail": "bob@corp.test"},
         test=[st(201), J, "L.set('booking2', j.bookingId);"]),
    item("11.5 Race: 5 parallel + 1 more for 13:00-14:00 -> exactly one winner", "POST", "/v1/resources/{{roomId}}/book",
         body={"startAt": "2026-11-20T13:00:00Z", "endAt": "2026-11-20T14:00:00Z", "organizerEmail": "late@corp.test"},
         desc="Pre-request fires 5 simultaneous bookings. Exactly ONE may succeed; this 6th request must then be 409.",
         pre=["const body = {startAt:'2026-11-20T13:00:00Z', endAt:'2026-11-20T14:00:00Z', organizerEmail:'race@corp.test'};",
              "const rs = await Promise.all([1,2,3,4,5].map(() => L.call('POST', '/v1/resources/' + L.v('roomId') + '/book', L.v('tokenAlice'), body)));",
              "L.set('raceCodes', JSON.stringify(rs.map(r => r.code)));"],
         test=[st(409, "6th concurrent booking is rejected"), "const codes = JSON.parse(L.v('raceCodes'));",
               "pm.test('exactly one of 5 parallel bookings won', () => pm.expect(codes.filter(c => c === 201).length).to.eql(1));",
               "pm.test('the other 4 were 409', () => pm.expect(codes.filter(c => c === 409).length).to.eql(4));"]),
    item("11.6 Reschedule booking2 to 12:00-13:00 (version 0)", "PUT", "/v1/resources/{{roomId}}/bookings/{{booking2}}", body={"startAt": "2026-11-20T12:00:00Z", "endAt": "2026-11-20T13:00:00Z", "version": 0},
         test=[st(200), J, "pm.test('version 1', () => pm.expect(j.version).to.eql(1));"]),
    item("11.7 Stale version -> 409", "PUT", "/v1/resources/{{roomId}}/bookings/{{booking2}}", body={"startAt": "2026-11-20T12:00:00Z", "endAt": "2026-11-20T13:00:00Z", "version": 0}, test=[st(409)]),
    item("11.8 Reschedule into conflict (10:00-11:00) -> 409", "PUT", "/v1/resources/{{roomId}}/bookings/{{booking2}}", body={"startAt": "2026-11-20T10:00:00Z", "endAt": "2026-11-20T11:00:00Z", "version": 1}, test=[st(409)]),
    item("11.9 Cancel booking1", "DELETE", "/v1/resources/{{roomId}}/bookings/{{booking1}}", test=[st(204)]),
    item("11.10 Slot is free again", "POST", "/v1/resources/{{roomId}}/book", body={"startAt": "2026-11-20T10:00:00Z", "endAt": "2026-11-20T11:00:00Z", "organizerEmail": "carol@corp.test"}, test=[st(201)]),
]))

F.append(folder("12 Notifications (SSE / WebSocket)", "Streaming itself is verified manually (curl / websocat – see TESTING_GUIDE). Automated here: access control.", [
    item("12.1 Alice cannot subscribe to Bob's stream -> 403", "GET", "/v1/notifications/stream?mailboxId={{bobId}}", headers={"Accept": "text/event-stream"}, test=[st(403)]),
    item("12.2 [MANUAL – do not run in Runner] Alice's SSE stream", "GET", "/v1/notifications/stream?mailboxId={{aliceId}}", headers={"Accept": "text/event-stream"},
         desc="Long-lived. Use Postman's SSE support or `curl -N`. Expect `event: ready` first, then `event: change` whenever Alice's mailbox changes. Heartbeat comments every 20s. Stop manually.",
         test=["pm.test('SSE content type', () => pm.expect(pm.response.headers.get('Content-Type')).to.include('text/event-stream'));"]),
]))

F.append(folder("13 Gateway resilience: auth, region gates, rate limit", "Token validation, regional drain/ramp, DRAINING priority rules, Bucket4j rate limiting.", [
    item("13.1 Malformed token -> 401", "GET", "/v1/mailboxes/{{aliceId}}/folders", token=None, headers={"Authorization": "Bearer not.a.jwt.at.all"}, test=[st(401)]),
    item("13.2 Expired token -> 401 (no IdP call)", "GET", "/v1/mailboxes/{{aliceId}}/folders", token=None, headers={"Authorization": "Bearer {{tokenExpired}}"},
         pre=["L.set('tokenExpired', L.mint('alice', L.v('tenantId'), L.v('aliceId'), 'AMER', {}, Math.floor(Date.now()/1000) - 60));"], test=[st(401)]),
    item("13.3 Admin API wrong key -> 403", "PUT", "/gateway-admin/regions/APAC", token=None, headers={"X-Admin-Key": "wrong"}, body={"state": "CLOSED"}, test=[st(403)]),
    item("13.4 List region gates", "GET", "/gateway-admin/regions", token=None, headers={"X-Admin-Key": "{{adminKey}}"},
         test=[st(200), J, "pm.test('three regions', () => pm.expect(j).to.have.keys(['APAC','EMEA','AMER']));"]),
    item("13.5 CLOSE region APAC", "PUT", "/gateway-admin/regions/APAC", token=None, headers={"X-Admin-Key": "{{adminKey}}"}, body={"state": "CLOSED"}, test=[st(200)]),
    item("13.6 Carol (APAC) is shed with Retry-After", "GET", "/v1/mailboxes/{{carolId}}/folders", token="tokenCarol", pre=WAIT(3000),
         desc="Gate cache TTL is 2s – wait 3s. Expect 503 + randomised Retry-After (5-60s) + X-Region-State.",
         test=[st(503), "const ra = Number(pm.response.headers.get('Retry-After'));", "pm.test('Retry-After 5..60', () => pm.expect(ra).to.be.within(5, 60));",
               "pm.test('X-Region-State', () => pm.expect(pm.response.headers.get('X-Region-State')).to.include('APAC:CLOSED'));"]),
    item("13.7 Alice (AMER) unaffected", "GET", "/v1/mailboxes/{{aliceId}}/folders", test=[st(200)]),
    item("13.8 DRAINING APAC", "PUT", "/gateway-admin/regions/APAC", token=None, headers={"X-Admin-Key": "{{adminKey}}"}, body={"state": "DRAINING"}, test=[st(200)]),
    item("13.9 DRAINING: interactive read still allowed", "GET", "/v1/mailboxes/{{carolId}}/folders", token="tokenCarol", pre=WAIT(3000), test=[st(200)]),
    item("13.10 DRAINING: background (search) is shed", "GET", "/v1/mailboxes/{{carolId}}/search?q=x", token="tokenCarol", test=[st(503), "pm.test('Retry-After present', () => pm.expect(pm.response.headers.get('Retry-After')).to.exist);"]),
    item("13.11 RAMPING 50% admits roughly half", "PUT", "/gateway-admin/regions/APAC", token=None, headers={"X-Admin-Key": "{{adminKey}}"}, body={"state": "RAMPING", "percent": 50},
         desc="Pre-request waits for the gate cache, then fires 40 requests; test asserts 25%..75% admitted (statistical; ~0.2% false-failure).",
         test=["pm.test('gate set', () => pm.response.to.have.status(200));",
               "await L.wait(3000);",
               "const rs = await Promise.all(Array.from({length: 40}, () => L.call('GET', '/v1/mailboxes/' + L.v('carolId') + '/folders', L.v('tokenCarol'))));",
               "const ok = rs.filter(r => r.code !== 503).length;", "console.log('admitted', ok, 'of 40');",
               "pm.test('~50% admitted (10..30 of 40)', () => pm.expect(ok).to.be.within(10, 30));"]),
    item("13.12 Staged restore via ramp API (25 -> 50 -> 100 -> OPEN)", "POST", "/gateway-admin/regions/APAC/ramp", token=None, headers={"X-Admin-Key": "{{adminKey}}"},
         body={"steps": [25, 50, 100], "intervalSeconds": 2}, test=[st(200), J, "pm.test('started', () => pm.expect(j.status).to.eql('RAMP_STARTED'));"]),
    item("13.13 After ~8s APAC is OPEN again", "GET", "/gateway-admin/regions", token=None, headers={"X-Admin-Key": "{{adminKey}}"}, pre=WAIT(8000),
         test=[st(200), J, "pm.test('APAC OPEN', () => pm.expect(j.APAC.state).to.eql('OPEN'));"]),
    item("13.14 Rate-limit probe (use Runner: 800 iterations, 0ms delay)", "GET", "/v1/mailboxes/{{aliceId}}/folders",
         desc="Default per-IP bucket is 600 burst + 100/s refill – see TESTING_GUIDE 13.14 for lowering it to see 429 quickly. Test accepts 200 or 429 and validates 429 headers.",
         test=["pm.test('200 or 429', () => pm.expect([200, 429]).to.include(pm.response.code));",
               "if (pm.response.code === 429) { pm.test('429 has Retry-After + scope', () => { pm.expect(pm.response.headers.get('Retry-After')).to.exist; pm.expect(pm.response.headers.get('X-RateLimit-Scope')).to.be.oneOf(['ip','user','tenant']); }); }"]),
]))

F.append(folder("14 Identity-provider degradation (outage drill)", "Reproduces the Nov-12 failure mode: IdP slow/down. Cached tokens keep working; unseen tokens fail fast with Retry-After. ALWAYS run 14.6 (restore).", [
    item("14.1 Warm the token cache (IdP healthy)", "GET", "/v1/mailboxes/{{aliceId}}/folders", test=[st(200)]),
    item("14.2 Break the IdP (8s latency + failure)", "POST", "", token=None, url="{{idpUrl}}/admin/state", body={"latency_ms": 8000, "fail": True}, test=[st(200)]),
    item("14.3 Cached token still works", "GET", "/v1/mailboxes/{{aliceId}}/folders", desc="Served from the SWR cache while the IdP is down.", test=[st(200), "pm.test('fast (<1.5s)', () => pm.expect(pm.response.responseTime).to.be.below(1500));"]),
    item("14.4 Never-seen token fails FAST with 503 + Retry-After", "GET", "/v1/mailboxes/{{aliceId}}/folders", token="tokenFresh",
         desc="Gateway upstream timeout is 1.5s – the request must not hang for the IdP's 8s.",
         test=[st(503), "pm.test('fast failure (<4s)', () => pm.expect(pm.response.responseTime).to.be.below(4000));", "pm.test('Retry-After set', () => pm.expect(Number(pm.response.headers.get('Retry-After'))).to.be.within(2, 15));"]),
    item("14.5 After the fresh window (65s) stale-while-revalidate still serves", "GET", "/v1/mailboxes/{{aliceId}}/folders", pre=WAIT(65000),
         desc="Fresh TTL 60s expired; stale window 300s -> served stale while a background refresh fails silently.", test=[st(200)]),
    item("14.6 RESTORE the IdP", "POST", "", token=None, url="{{idpUrl}}/admin/state", body={"latency_ms": 0, "fail": False}, test=[st(200)]),
    item("14.7 Unseen token works again", "GET", "/v1/mailboxes/{{aliceId}}/folders", token="tokenFresh", test=[st(200)]),
]))

F.append(folder("15 Cleanup", "Leaves the stack in a clean state.", [
    item("15.1 Region gates OPEN", "PUT", "/gateway-admin/regions/APAC", token=None, headers={"X-Admin-Key": "{{adminKey}}"}, body={"state": "OPEN"}, test=[st(200)]),
    item("15.2 IdP healthy", "POST", "", token=None, url="{{idpUrl}}/admin/state", body={"latency_ms": 0, "fail": False}, test=[st(200)]),
]))

COLLECTION_PRE = [
    "// Runs before EVERY request: refreshes the helper library and mints per-user tokens from the saved mailbox ids.",
    "const LIBSRC = pm.collectionVariables.get('lib');",
    "const L = new Function('pm','CryptoJS', LIBSRC)(pm, CryptoJS);",
    "const Z = '00000000-0000-0000-0000-000000000000';",
    "const id = k => L.v(k) || Z;",
    "const T1 = L.v('tenantId'), T2 = L.v('tenant2Id');",
    "L.set('tokenAdmin',   L.mint('admin',  T1, 'aaaaaaaa-0000-0000-0000-00000000000a', 'AMER'));",
    "L.set('tokenAlice',   L.mint('alice',  T1, id('aliceId'),  'AMER'));",
    "L.set('tokenBob',     L.mint('bob',    T1, id('bobId'),    'AMER'));",
    "L.set('tokenShared',  L.mint('support',T1, id('sharedId'), 'AMER'));",
    "L.set('tokenCarol',   L.mint('carol',  T1, id('carolId'),  'APAC'));",
    "L.set('tokenTenant2', L.mint('dave',   T2, id('daveId'),   'AMER'));",
    "L.set('tokenFresh',   L.mint('alice',  T1, id('aliceId'),  'AMER', { jti: String(Date.now()) + Math.random() }));",
]

def build():
    # collection variables
    names = ["lib", "runId", "aliceId", "bobId", "sharedId", "carolId", "daveId", "aliceInboxId", "aliceSentId", "aliceArchiveId", "aliceDeletedId", "aliceProjectsId",
             "bobInboxId", "bobSentId", "bobArchiveId", "sentMessageId", "convId", "bobMessageId", "bobMsgVersion", "bobUnreadBefore", "page1Ids", "page1LastAt", "cursor1",
             "deltaToken0", "deltaToken1", "deltaCount", "attId", "bobAttId", "uploadUrl", "downloadUrl", "oofCount", "eventSingleId", "recurId", "recurVersion",
             "bobEventId", "roomId", "booking1", "booking2", "raceCodes", "tokenAdmin", "tokenAlice", "tokenBob", "tokenShared", "tokenCarol", "tokenTenant2", "tokenFresh", "tokenExpired"]
    variables = [{"key": n, "value": LIB if n == "lib" else ""} for n in names]
    coll = {
        "info": {"_postman_id": str(uuid.uuid4()), "name": "Enterprise Outlook – Full System Test",
                 "description": "End-to-end functional, resilience and security tests for the Enterprise Outlook platform. See TESTING_GUIDE.md. Run folders 00 -> 15 in order (Collection Runner, delay 300 ms, persist variables ON).",
                 "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
        "event": [ev("prerequest", COLLECTION_PRE[2:], needs_lib=False)],
        "variable": variables,
        "item": F,
    }
    # collection-level pre-request needs LIBSRC defined first
    coll["event"][0]["script"]["exec"] = COLLECTION_PRE
    env = {"id": str(uuid.uuid4()), "name": "Enterprise Outlook – Local Docker", "_postman_variable_scope": "environment",
           "values": [{"key": k, "value": v, "enabled": True} for k, v in [
               ("baseUrl", "http://localhost:8080"), ("idpUrl", "http://localhost:9099"), ("envoyAdminUrl", "http://localhost:9901"),
               ("adminKey", "change-me"), ("tenantId", T1), ("tenant2Id", T2)]]}
    json.dump(coll, open(os.path.join(HERE, "Enterprise-Outlook.postman_collection.json"), "w"), indent=2)
    json.dump(env, open(os.path.join(HERE, "Enterprise-Outlook.local.postman_environment.json"), "w"), indent=2)
    n = sum(len(f["item"]) for f in F)
    print("folders:", len(F), "requests:", n)

if __name__ == "__main__":
    build()
