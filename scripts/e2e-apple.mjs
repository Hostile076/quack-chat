/**
 * Quack Chat 全功能端到端测试（协议层，Node 24 内置 WebSocket / fetch）。
 *
 * 覆盖：注册 / 登录 / 搜索 / 好友申请与备注 / 私聊收发 / ACK / 历史 / 增量同步 /
 *       已读回执 / 未读 / 撤回 / 编辑 / 正在输入 / 群搜索与入群审批 / 群消息 /
 *       群公告 / 群昵称 / 群管理 / 精华 / 空间动态 / 评论 / 转发 / 文件上传下载 /
 *       离线补发 / 多端登录 / 通话信令 / 心跳。
 *
 * 用法：node scripts/e2e-apple.mjs [ws://127.0.0.1:18080/ws]
 */

const BASE = process.argv[2] || 'ws://127.0.0.1:18080/ws';
const HTTP = BASE.replace(/^ws/, 'http').replace(/\/ws$/, '');

let passed = 0;
const failures = [];
const notes = [];

function ok(name, detail = '') {
    passed++;
    console.log(`PASS ${name}${detail ? ' — ' + detail : ''}`);
}

function fail(name, error) {
    failures.push(name + ' :: ' + (error && error.message ? error.message : String(error)));
    console.log(`FAIL ${name} — ${error && error.message ? error.message : error}`);
}

async function check(name, fn) {
    try {
        await fn();
        ok(name);
    } catch (error) {
        fail(name, error);
    }
}

function assert(condition, message) {
    if (!condition) throw new Error(message || 'assertion failed');
}

function eq(actual, expected, message) {
    if (actual !== expected) throw new Error(`${message || 'value mismatch'}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
}

class Conn {
    constructor(label) {
        this.label = label;
        this.seq = 0;
        this.pending = new Map();
        this.pushes = [];
        this.waiters = [];
        this.closedCode = null;
    }

    open() {
        return new Promise((resolve, reject) => {
            this.ws = new WebSocket(BASE);
            const timer = setTimeout(() => reject(new Error(`${this.label}: connect timeout`)), 8000);
            this.ws.onopen = () => {
                clearTimeout(timer);
                resolve();
            };
            this.ws.onerror = () => reject(new Error(`${this.label}: connect error`));
            this.ws.onclose = (event) => {
                this.closedCode = event.code;
                for (const waiter of this.waiters.splice(0)) {
                    waiter.reject(new Error(`${this.label}: closed ${event.code}`));
                }
                for (const [, entry] of this.pending) {
                    clearTimeout(entry.timer);
                    entry.reject(new Error(`${this.label}: closed ${event.code}`));
                }
                this.pending.clear();
            };
            this.ws.onmessage = (event) => {
                let envelope;
                try {
                    envelope = JSON.parse(event.data);
                } catch {
                    return;
                }
                if (envelope.seq && this.pending.has(envelope.seq)) {
                    const entry = this.pending.get(envelope.seq);
                    this.pending.delete(envelope.seq);
                    clearTimeout(entry.timer);
                    entry.resolve(envelope);
                    return;
                }
                this.pushes.push({ cmd: envelope.cmd, data: envelope.data, time: Date.now() });
                if (this.pushes.length > 500) this.pushes.shift();
                for (const waiter of [...this.waiters]) {
                    if (waiter.cmd === envelope.cmd && (!waiter.pred || waiter.pred(envelope))) {
                        this.waiters.splice(this.waiters.indexOf(waiter), 1);
                        clearTimeout(waiter.timer);
                        waiter.resolve(envelope);
                    }
                }
            };
        });
    }

    request(cmd, data = null, timeout = 8000) {
        const seq = ++this.seq;
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(seq);
                reject(new Error(`${this.label}: ${cmd} timeout`));
            }, timeout);
            this.pending.set(seq, { resolve, reject, timer });
            this.ws.send(JSON.stringify({ cmd, seq, code: 0, msg: null, data }));
        });
    }

    async call(cmd, data = null) {
        const response = await this.request(cmd, data);
        if (response.code !== 0) {
            throw new Error(`${cmd} -> ${response.code} ${response.msg}`);
        }
        return response.data;
    }

    /** 只发不等（fire-and-forget 命令：typing / msg.read 旧语义 / 通话控制） */
    send(cmd, data = null) {
        this.ws.send(JSON.stringify({ cmd, seq: ++this.seq, code: 0, msg: null, data }));
    }

    waitFor(cmd, pred = null, timeout = 6000) {
        const existing = this.pushes.findIndex((push) => push.cmd === cmd && (!pred || pred({ cmd: push.cmd, data: push.data })));
        if (existing >= 0) {
            const [found] = this.pushes.splice(existing, 1);
            return Promise.resolve({ cmd: found.cmd, data: found.data });
        }
        return new Promise((resolve, reject) => {
            const waiter = { cmd, pred, resolve, reject, timer: null };
            waiter.timer = setTimeout(() => {
                this.waiters.splice(this.waiters.indexOf(waiter), 1);
                reject(new Error(`${this.label}: wait ${cmd} timeout`));
            }, timeout);
            this.waiters.push(waiter);
        });
    }

    drain(cmd) {
        this.pushes = this.pushes.filter((push) => push.cmd !== cmd);
    }

    close() {
        try {
            this.ws.close();
        } catch {
            // ignore
        }
    }
}

const stamp = Date.now().toString().slice(-7);
const nameA = 'zoe' + stamp;
const nameB = 'yan' + stamp;
const nameC = 'xu' + stamp;

let a;
let b;
let c;
let aQc;
let bQc;

async function login(conn, account, password, deviceId) {
    const data = await conn.call('login', { account, password, deviceId });
    assert(data && data.user, 'login response missing user');
    return data;
}

/** 上传一个 1×1 PNG 当头像，返回服务端 URL（用来验证券/头像同步） */
async function uploadAvatar(token) {
    const png = new Uint8Array([
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d,
        0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4, 0x89, 0x00, 0x00, 0x00,
        0x0a, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0d, 0x0a, 0x2d, 0xb4, 0x00, 0x00, 0x00, 0x00, 0x49,
        0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82
    ]);
    const form = new FormData();
    form.append('file', new Blob([png], { type: 'image/png' }), 'avatar.png');
    const response = await fetch(HTTP + '/api/upload', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + token },
        body: form
    });
    assert(response.status === 200, '头像上传失败: ' + response.status);
    const data = await response.json();
    return data.url;
}

async function main() {
    console.log('== Quack Chat e2e (' + BASE + ') ==');

    // ---- 0. 服务可用性 ----
    await check('healthz 可用', async () => {
        const response = await fetch(HTTP + '/healthz');
        eq(response.status, 200, 'healthz status');
    });

    // ---- 0.5 演示数据：15 个「演示账号i」 ----
    await check('演示账号 1（用户名登录）', async () => {
        const demo = new Conn('DEMO');
        await demo.open();
        const data = await login(demo, '演示账号1', '123456', 'device-demo');
        eq(String(data.user.qcId), '10001', '演示账号1 应为 10001');
        assert(data.user.username === '演示账号1', '用户名不对: ' + data.user.username);
        eq(data.friends.length, 14, '演示账号1 应有 14 个好友');
        eq(data.groups.length, 2, '演示账号1 应在 2 个群里');
        demo.close();
    });
    await check('演示账号 15（QC 号登录）', async () => {
        const demo = new Conn('DEMO15');
        await demo.open();
        const data = await login(demo, '10015', '123456', 'device-demo15');
        assert(data.user.username === '演示账号15', '10015 的用户名不对: ' + data.user.username);
        eq(data.friends.length, 14, '演示账号15 应有 14 个好友');
        eq(data.groups.length, 1, '演示账号15 只在 q10001');
        demo.close();
    });
    await check('演示群成员数（q10001 15 人 / q10002 8 人）', async () => {
        const demo = new Conn('DEMO-GROUP');
        await demo.open();
        await login(demo, '演示账号1', '123456', 'device-demo-group');
        const first = await demo.call('group.detail', { groupId: 'q10001' });
        eq(first.detail.memberCount, 15, 'q10001 成员数');
        assert(first.detail.members.every((member) => member.nickname && member.nickname.startsWith('演示账号')),
                'q10001 成员昵称应全部是演示账号i');
        const second = await demo.call('group.detail', { groupId: 'q10002' });
        eq(second.detail.memberCount, 8, 'q10002 成员数');
        demo.close();
    });
    // ---- 1. 注册（QC 号系统分配） ----
    a = new Conn('A');
    await a.open();
    const regA = await a.call('register', { username: nameA, password: '123456' });
    aQc = regA.user.qcId;
    assert(Number(aQc) >= 10001, 'QC 号应从 10001 开始');
    await check('注册返回系统分配 QC 号', async () => {
        assert(regA.user.username === nameA, 'username mismatch');
    });

    b = new Conn('B');
    await b.open();
    const regB = await b.call('register', { username: nameB, password: '123456' });
    bQc = regB.user.qcId;

    // ---- 2. 登录 ----
    let loginA;
    await check('登录成功并返回 token', async () => {
        loginA = await login(a, nameA, '123456', 'device-a');
        assert(loginA.token && loginA.token.length > 10, 'token 缺失');
        assert(Array.isArray(loginA.friends), 'friends 不是数组');
        assert(Array.isArray(loginA.groups), 'groups 不是数组');
    });
    let loginB;
    await check('B 登录成功', async () => {
        loginB = await login(b, nameB, '123456', 'device-b');
    });

    let avatarA;
    let avatarB;
    await check('A 设置头像', async () => {
        avatarA = await uploadAvatar(loginA.token);
        await a.call('user.update', {
            username: nameA, nickname: null, avatar: avatarA,
            signature: null, gender: null, birthday: null, region: null, identity: null
        });
    });
    await check('B 设置头像', async () => {
        avatarB = await uploadAvatar(loginB.token);
        await b.call('user.update', {
            username: nameB, nickname: null, avatar: avatarB,
            signature: null, gender: null, birthday: null, region: null, identity: null
        });
    });

    // ---- 3. 搜索 ----
    await check('按 QC 号搜索精确命中', async () => {
        const found = await a.call('friend.search', { keyword: String(bQc) });
        assert(found.users.some((user) => String(user.qcId) === String(bQc)), 'QC 精确搜索未命中');
    });
    await check('按用户名搜索命中（数字除外）', async () => {
        const found = await a.call('friend.search', { keyword: nameB });
        assert(found.users.some((user) => user.username === nameB), '用户名搜索未命中');
    });
    await check('搜索不包含自己', async () => {
        const found = await a.call('friend.search', { keyword: nameA });
        assert(!found.users.some((user) => String(user.qcId) === String(aQc)), '搜索结果包含自己');
    });

    // ---- 4. 好友申请 + 备注 + 同意 ----
    await check('发送好友申请（带备注）', async () => {
        const response = await a.call('friend.request', {
            toId: String(bQc), message: '你好，我是 ' + nameA, remark: '我的好友 A'
        });
        assert(response.request && response.request.id, '缺少申请 id');
    });
    await check('对方收到好友申请推送', async () => {
        const push = await b.waitFor('friend.request.push');
        assert(push.data, 'empty push');
    });
    let incomingRequestId;
    await check('被申请人能看到申请列表', async () => {
        const list = await b.call('friend.requests', {});
        assert(list.incoming.length >= 1, 'incoming 为空');
        incomingRequestId = list.incoming[0].id;
        eq(list.incoming[0].fromAvatar, avatarA, '申请列表里的申请人头像');
    });
    await check('同意好友申请：申请人收到 friend.added 推送', async () => {
        const response = await b.call('friend.handle', { requestId: incomingRequestId, accept: true });
        assert(response.newFriend, '处理结果缺少 newFriend');
        eq(response.newFriend.avatar, avatarA, '同意后回包里的好友头像');
        const pushA = await a.waitFor('friend.added');
        assert(String(pushA.data.userId) === String(bQc), 'friend.added 的 userId 不对');
        eq(pushA.data.avatar, avatarB, 'friend.added 推送里的好友头像');
    });
    await check('好友列表包含双方', async () => {
        const listA = await a.call('friend.requests', {});
        assert(Array.isArray(listA.incoming), 'requests 结构异常');
        // 重新登录拿到好友列表
        const relogin = await login(a, nameA, '123456', 'device-a');
        const friendB = relogin.friends.find((friend) => friend.userId === String(bQc));
        assert(friendB, 'A 的好友列表缺少 B');
        eq(friendB.avatar, avatarB, '登录响应里的好友头像');
    });
    await check('设置好友备注', async () => {
        await a.call('friend.remark', { friendId: String(bQc), remark: '备注-B' });
        const relogin = await login(a, nameA, '123456', 'device-a');
        const friend = relogin.friends.find((item) => item.userId === String(bQc));
        assert(friend && friend.remark === '备注-B', '备注未生效：' + JSON.stringify(friend));
    });

    // ---- 5. 私聊消息 ----
    let firstMessageId;
    let firstSeq;
    await check('私聊发送并收到 ACK（含 seq/ts）', async () => {
        const ack = await a.call('msg.send', {
            clientMsgId: 'c-' + stamp + '-1', to: String(bQc), chatType: 'PRIVATE',
            msgType: 'TEXT', content: '第一条私聊'
        });
        assert(ack.messageId, 'ACK 缺少 messageId');
        assert(ack.seq > 0, 'ACK 缺少会话 seq');
        firstMessageId = ack.messageId;
        firstSeq = ack.seq;
    });
    await check('对端收到 msg.push', async () => {
        const push = await b.waitFor('msg.push');
        assert(String(push.data.from) === String(aQc), 'push.from 不对');
        assert(push.data.content === '第一条私聊', 'push content 不对');
    });
    await check('历史消息可拉取', async () => {
        const history = await a.call('history.req', { peerId: String(bQc), chatType: 'PRIVATE', beforeTs: Date.now() + 1000, limit: 50 });
        assert(history.messages.some((message) => message.content === '第一条私聊'), '历史里没有刚发的消息');
    });
    await check('增量同步 sinceSeq=0 返回最新', async () => {
        const sync = await a.call('sync.req', { peerId: String(bQc), chatType: 'PRIVATE', sinceSeq: 0, limit: 20 });
        assert(sync.messages.length >= 1, 'sync 无消息');
        assert(sync.latestSeq >= firstSeq, 'latestSeq 不正确');
    });
    await check('增量同步 sinceSeq=firstSeq-1 命中该条', async () => {
        const sync = await a.call('sync.req', { peerId: String(bQc), chatType: 'PRIVATE', sinceSeq: firstSeq - 1, limit: 20 });
        assert(sync.messages.some((message) => message.id === firstMessageId), 'sync 未返回目标消息');
    });

    // ---- 6. 已读 / 未读 ----
    await check('B 上报已读，A 收到 msg.read.push', async () => {
        await b.call('msg.read', { peerId: String(aQc), chatType: 'PRIVATE', ts: Date.now(), seq: firstSeq });
        const push = await a.waitFor('msg.read.push');
        assert(String(push.data.readerId) === String(bQc), 'readerId 不对');
    });
    await check('未读数随已读清零', async () => {
        const relogin = await login(b, nameB, '123456', 'device-b');
        const unread = relogin.unread || {};
        const key = 'PRIVATE:' + Math.min(Number(aQc), Number(bQc)) + ':' + Math.max(Number(aQc), Number(bQc));
        assert(!unread[key] || unread[key] === 0, '未读数未清零：' + JSON.stringify(unread));
    });

    // ---- 7. 编辑 / 撤回 ----
    await check('编辑消息，对端收到 msg.edited', async () => {
        await a.call('msg.edit', { messageId: firstMessageId, content: '第一条私聊（已编辑）' });
        const push = await b.waitFor('msg.edited');
        assert(push.data.content === '第一条私聊（已编辑）', '编辑内容未推送');
        assert(push.data.edited === true, 'edited 标记缺失');
    });
    await check('撤回消息，对端收到 msg.recalled', async () => {
        await a.call('msg.recall', { messageId: firstMessageId });
        const push = await b.waitFor('msg.recalled');
        assert(String(push.data.id) === String(firstMessageId) || String(push.data.messageId) === String(firstMessageId), '撤回推送 id 不对');
    });
    await check('重复撤回被拒绝（非 0 码）', async () => {
        const response = await a.request('msg.recall', { messageId: firstMessageId });
        assert(response.code !== 0, '重复撤回居然成功');
    });

    // ---- 8. 正在输入 / 心跳 ----
    await check('typing 推送给对端', async () => {
        a.send('typing', { to: String(bQc), chatType: 'PRIVATE' });
        const push = await b.waitFor('typing');
        assert(String(push.data.from) === String(aQc), 'typing.from 不对');
    });
    await check('心跳有响应', async () => {
        const response = await a.call('heartbeat', {});
        assert(response !== undefined, 'heartbeat 无响应');
    });

    // ---- 9. 群：搜索 / 入群审批 / 消息 / 公告 / 群昵称 ----
    const groupId = 'q10001';
    let owner;
    let joinRequestId;
    let cQc;
    let eConn;
    let eQc;

    await check('群主（种子账号 alice）登录', async () => {
        owner = new Conn('OWNER');
        await owner.open();
        await login(owner, '演示账号1', '123456', 'device-owner');
    });
    await check('搜索群（按群号）', async () => {
        const result = await owner.call('group.search', { keyword: groupId });
        assert(result.groups.some((group) => group.id === groupId), '未搜到 q10001');
    });
    await check('C 申请入群', async () => {
        c = new Conn('C');
        await c.open();
        const regC = await c.call('register', { username: nameC, password: '123456' });
        cQc = regC.user.qcId;
        await c.call('login', { account: nameC, password: '123456', deviceId: 'device-c' });
        const result = await c.call('group.join', { groupId });
        assert(result, '入群申请无响应');
        assert(result.status, 'join 响应缺少 status');
        if (result.status !== 'APPROVED' && result.status !== 'MEMBER') {
            joinRequestId = result.id;
        }
    });
    await check('群主能看到入群申请并同意', async () => {
        const list = await owner.call('group.join.requests', {});
        if (joinRequestId) {
            assert(list.requests.some((request) => request.id === joinRequestId), '申请列表缺少刚提交的申请');
            await owner.call('group.join.handle', { requestId: joinRequestId, accept: true });
        }
    });
    await check('入群后群详情包含新成员', async () => {
        const data = await owner.call('group.detail', { groupId });
        assert(data.detail.members.some((member) => member.userId === String(cQc)),
                '群成员里没有 C：' + JSON.stringify(data.detail.members.map((member) => member.userId)));
    });
    await check('群消息广播给新成员', async () => {
        c.drain('msg.push');
        const ack = await owner.call('msg.send', {
            clientMsgId: 'g-' + stamp + '-1', to: groupId, chatType: 'GROUP',
            msgType: 'TEXT', content: '群消息测试'
        });
        assert(ack.seq > 0, '群消息缺少 seq');
        const push = await c.waitFor('msg.push', (envelope) => envelope.data.content === '群消息测试');
        assert(push.data.content === '群消息测试', '群消息未广播');
    });
    await check('普通成员不能发布群公告（403）', async () => {
        const response = await c.request('group.announcement.add', { groupId, text: '越权公告' });
        eq(response.code, 403, '越权发公告应被拒绝');
    });
    await check('群公告可发布且详情可见', async () => {
        await owner.call('group.announcement.add', { groupId, text: '公告 ' + stamp });
        const data = await owner.call('group.detail', { groupId });
        const texts = (data.detail.announcements || []).map((item) => item.text);
        assert(texts.includes('公告 ' + stamp), '公告未出现在详情里：' + JSON.stringify(texts));
    });
    await check('群昵称（alias）生效', async () => {
        await c.call('group.alias', { groupId, alias: '小名' + stamp });
        const data = await owner.call('group.detail', { groupId });
        const me = data.detail.members.find((member) => member.userId === String(cQc));
        assert(me && me.alias === '小名' + stamp, '别名未生效：' + JSON.stringify(me));
    });
    await check('邀请新成员进群', async () => {
        eConn = new Conn('E');
        await eConn.open();
        const regE = await eConn.call('register', { username: 'ivy' + stamp, password: '123456' });
        eQc = regE.user.qcId;
        await eConn.call('login', { account: 'ivy' + stamp, password: '123456', deviceId: 'device-e' });
        await owner.call('group.invite', { groupId, userIds: [String(eQc)] });
        const detail = await owner.call('group.detail', { groupId });
        assert(detail.detail.members.some((member) => member.userId === String(eQc)), '邀请后成员列表没有新成员');
        const relogin = await login(eConn, 'ivy' + stamp, '123456', 'device-e');
        assert(relogin.groups.some((group) => group.id === groupId), '被邀请者的群列表没有该群');
    });
    await check('设置 / 取消群管理员', async () => {
        await owner.call('group.admin.add', { groupId, userId: String(cQc) });
        let data = await owner.call('group.detail', { groupId });
        let member = data.detail.members.find((item) => item.userId === String(cQc));
        assert(member && member.role === 'ADMIN', '角色未变成 ADMIN：' + JSON.stringify(member));
        // 管理员也可以发公告
        await c.call('group.announcement.add', { groupId, text: '管理员公告 ' + stamp });
        await owner.call('group.admin.remove', { groupId, userId: String(cQc) });
        data = await owner.call('group.detail', { groupId });
        member = data.detail.members.find((item) => item.userId === String(cQc));
        assert(member && member.role === 'MEMBER', '角色未恢复：' + JSON.stringify(member));
    });
    await check('添加 / 删除精华消息', async () => {
        const ack = await owner.call('msg.send', {
            clientMsgId: 'e-' + stamp, to: groupId, chatType: 'GROUP', msgType: 'TEXT', content: '要珍藏的消息'
        });
        await owner.call('group.essence.add', { groupId, userId: String(aQc), messageId: ack.messageId });
        let list = await owner.call('group.essence.list', { groupId });
        assert(list.messages.some((message) => message.id === ack.messageId), '精华列表缺少消息');
        await owner.call('group.essence.remove', { groupId, messageId: ack.messageId });
        list = await owner.call('group.essence.list', { groupId });
        assert(!list.messages.some((message) => message.id === ack.messageId), '精华消息未删除');
    });
    await check('退群：本人收到 group.removed.push 且失去访问权限', async () => {
        eConn.drain('group.removed.push');
        await eConn.call('group.quit', { groupId });
        const push = await eConn.waitFor('group.removed.push');
        eq(push.data.reason, 'quit', '退群原因');
        const response = await eConn.request('group.detail', { groupId });
        assert(response.code === 403 || response.code === 404, '退群后仍能看群详情');
    });
    await check('同账号多端：改头像会推送给其它设备', async () => {
        const device1 = new Conn('MULTI1');
        const device2 = new Conn('MULTI2');
        await device1.open();
        await device2.open();
        await login(device1, '演示账号5', '123456', 'multi-device-a');
        await login(device2, '演示账号5', '123456', 'multi-device-b');
        const avatar = 'http://example.com/avatar-multi.png';
        await device1.call('user.update', {
            username: '演示账号5', nickname: null, avatar,
            signature: null, gender: null, birthday: null, region: null, identity: null
        });
        const push = await device2.waitFor('friend.updated');
        eq(push.data.avatar, avatar, '其它设备收到的头像');
        device1.close();
        device2.close();
    });
    await check('同群非好友：改头像也会推给群成员', async () => {
        const avatar = 'http://example.com/avatar-group-member.png';
        await owner.call('user.update', {
            username: '演示账号1', nickname: null, avatar,
            signature: null, gender: null, birthday: null, region: null, identity: null
        });
        const push = await c.waitFor('friend.updated',
                (envelope) => String(envelope.data.qcId) === '10001');
        eq(String(push.data.qcId), '10001', '群成员的推送来源');
        eq(push.data.avatar, avatar, '群成员收到的头像');
    });
    await check('踢人：被踢者收到 group.removed.push 且失去访问权限', async () => {
        c.drain('group.removed.push');
        await owner.call('group.kick', { groupId, userId: String(cQc) });
        const push = await c.waitFor('group.removed.push');
        eq(push.data.reason, 'kicked', '被踢原因');
        const response = await c.request('group.detail', { groupId });
        assert(response.code === 403 || response.code === 404, '被踢后仍能看群详情');
    });
    await check('转让群主 / 解散群聊（q10002）', async () => {
        // q10002 的群主是演示账号2（10002），先转给演示账号1（10001）再解散
        const demo2 = new Conn('DEMO2');
        await demo2.open();
        await login(demo2, '10002', '123456', 'device-demo2');
        await demo2.call('group.transfer', { groupId: 'q10002', userId: '10001' });
        let detail = await owner.call('group.detail', { groupId: 'q10002' });
        eq(detail.detail.ownerId, '10001', '群主转让未生效');
        await owner.call('group.dissolve', { groupId: 'q10002' });
        const response = await demo2.request('group.detail', { groupId: 'q10002' });
        assert(response.code === 403 || response.code === 404, '解散后仍能看群详情');
        demo2.close();
    });

    // ---- 11. QC 空间 ----
    let momentId;
    await check('发布动态（文字）', async () => {
        const result = await a.call('moment.publish', { text: '动态 ' + stamp, media: [], visibility: 'PUBLIC' });
        const moment = result.moment || result;
        assert(moment.id, '发布动态缺少 id');
        momentId = moment.id;
    });
    await check('动态列表可见', async () => {
        const list = await a.call('moment.list', { qcId: String(aQc), beforeTs: 0, limit: 20 });
        assert(list.moments.some((moment) => moment.id === momentId), '动态列表缺少刚发的动态');
    });
    await check('点赞一次成功', async () => {
        const result = await a.call('moment.like', { momentId, liked: true });
        assert(result.moment, 'like 响应缺少 moment');
        assert(result.moment.likes >= 1, 'likes 未增加');
    });
    await check('重复点赞不会无限增长', async () => {
        const first = await a.call('moment.like', { momentId, liked: true });
        const second = await a.call('moment.like', { momentId, liked: true });
        eq(second.moment.likes, first.moment.likes, '重复点赞改变了计数');
    });
    await check('评论新增 / 列表 / 删除', async () => {
        await b.call('moment.comment.add', { momentId, text: '评论 ' + stamp });
        const list = await b.call('moment.comment.list', { momentId });
        assert(list.comments.length >= 1, '评论列表为空');
        const commentId = list.comments[0].id;
        await b.call('moment.comment.delete', { momentId, commentId });
        const after = await b.call('moment.comment.list', { momentId });
        assert(!after.comments.some((comment) => comment.id === commentId), '评论未删除');
    });
    await check('编辑动态', async () => {
        await a.call('moment.edit', { momentId, text: '动态 ' + stamp + '（编辑）', visibility: 'PUBLIC' });
        const list = await a.call('moment.list', { qcId: String(aQc), beforeTs: 0, limit: 20 });
        const moment = list.moments.find((item) => item.id === momentId);
        assert(moment.text.includes('编辑'), '编辑未生效');
    });
    await check('转发动态', async () => {
        const result = await b.call('moment.share', { sourceId: momentId, text: '转发一下', visibility: 'PUBLIC' });
        assert(result.moment && result.moment.shared, '转发缺少 shared 快照');
    });
    await check('点赞列表 / 相册 / 统计', async () => {
        const likes = await a.call('moment.likes', { momentId });
        assert(Array.isArray(likes.users), 'likes.users 不是数组');
        const media = await a.call('moment.media', { qcId: String(aQc), beforeTs: 0, limit: 10 });
        assert(Array.isArray(media.items), 'media.items 不是数组');
        const stats = await a.call('moment.stats', { qcId: String(aQc) });
        assert(stats.posts >= 1, 'stats.posts 异常：' + JSON.stringify(stats));
    });
    await check('删除动态', async () => {
        await a.call('moment.delete', { momentId });
        const list = await a.call('moment.list', { qcId: String(aQc), beforeTs: 0, limit: 20 });
        assert(!list.moments.some((moment) => moment.id === momentId), '动态未删除');
    });

    // ---- 12. 文件上传 / 下载 ----
    await check('带 token 上传文件', async () => {
        const form = new FormData();
        form.append('file', new Blob([new Uint8Array([1, 2, 3, 4, 5, 6])], { type: 'application/octet-stream' }), 'hello.bin');
        const response = await fetch(HTTP + '/api/upload', {
            method: 'POST',
            headers: { Authorization: 'Bearer ' + loginA.token },
            body: form
        });
        eq(response.status, 200, 'upload status');
        const data = await response.json();
        assert(data.id && data.url, '上传响应缺少 id/url');
        const download = await fetch(HTTP + data.url);
        eq(download.status, 200, 'signed download status');
        const bytes = new Uint8Array(await download.arrayBuffer());
        eq(bytes.length, 6, 'download length');
    });
    await check('未登录上传被拒绝', async () => {
        const form = new FormData();
        form.append('file', new Blob([new Uint8Array([1])], { type: 'application/octet-stream' }), 'x.bin');
        const response = await fetch(HTTP + '/api/upload', { method: 'POST', body: form });
        assert(response.status === 401 || response.status === 403, '未登录上传没有被拒绝：' + response.status);
    });

    // ---- 13. 离线补发 + 多端 ----
    await check('离线消息在登录后补发', async () => {
        const offline = new Conn('D');
        await offline.open();
        await offline.call('register', { username: 'off' + stamp, password: '123456' });
        await offline.call('login', { account: 'off' + stamp, password: '123456', deviceId: 'device-off' });
        offline.close();
        // 给离线账号发一条消息：先加好友
        const search = await a.call('friend.search', { keyword: 'off' + stamp });
        const offQc = search.users[0].qcId;
        const request = await a.call('friend.request', { toId: String(offQc), message: 'hi' });
        // 用账号自己上线同意
        const off2 = new Conn('D2');
        await off2.open();
        const offLogin = await off2.call('login', { account: 'off' + stamp, password: '123456', deviceId: 'device-off2' });
        const requests = await off2.call('friend.requests', {});
        const pending = requests.incoming.find((item) => String(item.fromId) === String(aQc));
        assert(pending, '离线账号没收到好友申请');
        await off2.call('friend.handle', { requestId: pending.id, accept: true });
        off2.close();
        await new Promise((resolve) => setTimeout(resolve, 200));
        await a.call('msg.send', {
            clientMsgId: 'off-' + stamp, to: String(offQc), chatType: 'PRIVATE',
            msgType: 'TEXT', content: '离线消息'
        });
        const off3 = new Conn('D3');
        await off3.open();
        await off3.call('login', { account: 'off' + stamp, password: '123456', deviceId: 'device-off' });
        const push = await off3.waitFor('msg.push', null, 8000);
        assert(push.data.content === '离线消息', '离线补发内容不对');
        off3.close();
    });
    await check('同账号多端都能收到推送', async () => {
        const second = new Conn('A2');
        await second.open();
        await login(second, nameA, '123456', 'device-a2');
        b.drain('msg.push');
        await b.call('msg.send', {
            clientMsgId: 'multi-' + stamp, to: String(aQc), chatType: 'PRIVATE',
            msgType: 'TEXT', content: '多端消息'
        });
        const hasMulti = (envelope) => envelope.data.content === '多端消息';
        const [push1, push2] = await Promise.all([
            a.waitFor('msg.push', hasMulti, 8000),
            second.waitFor('msg.push', hasMulti, 8000)
        ]);
        assert(push1.data.content === '多端消息' && push2.data.content === '多端消息', '多端推送不一致');
        second.close();
    });
    await check('同 deviceId 再次登录会踢掉旧连接（4001）', async () => {
        const kicked = new Conn('A3');
        await kicked.open();
        await login(kicked, nameA, '123456', 'device-kick');
        // 先挂上监听，再触发“同设备新登录”，否则可能错过关闭事件
        const closeCode = new Promise((resolve) => {
            const timer = setTimeout(() => resolve(-1), 5000);
            kicked.ws.addEventListener('close', (event) => {
                clearTimeout(timer);
                resolve(event.code);
            });
        });
        const newer = new Conn('A4');
        await newer.open();
        await login(newer, nameA, '123456', 'device-kick');
        eq(await closeCode, 4001, '旧连接关闭码');
        newer.close();
    });

    // ---- 14. 通话信令 ----
    await check('通话邀请 / 接听 / 挂断信令', async () => {
        // callId 由服务端分配：客户端发起时传 null，回包/来电里拿真实 id
        const invited = await a.call('call.invite', { callId: null, toId: String(bQc) });
        const callId = invited.callId;
        assert(callId, 'invite 回包缺少 callId');
        const incoming = await b.waitFor('call.incoming');
        eq(incoming.data.callId, callId, 'incoming callId 不对');
        await b.call('call.accept', { callId, toId: String(aQc) });
        await a.waitFor('call.answered');
        await a.call('call.hangup', { callId, toId: String(bQc) });
        await b.waitFor('call.ended');
    });

    // ---- 15. 资料 ----
    await check('查看资料 / 点赞 / 修改资料', async () => {
        const profile = await a.call('user.profile', { qcId: String(bQc) });
        assert(profile.profile, '资料为空');
        const liked = await a.call('user.like', { qcId: String(bQc) });
        assert(liked.likes >= 1, '点赞没有增加');
        assert(liked.qcId, '点赞响应缺少 qcId');
        await a.call('user.update', {
            username: nameA, nickname: nameA + '昵称', avatar: null,
            signature: '签名 ' + stamp, gender: '男', birthday: '2000-01-01',
            region: '测试', identity: '学生'
        });
        const mine = await a.call('user.profile', { qcId: String(aQc) });
        assert(mine.profile.signature === '签名 ' + stamp, '签名未保存');
    });

    // ---- 16. 创建群聊 ----
    await check('创建群聊：拉好友进群并推送给成员', async () => {
        b.drain('group.updated.push');
        const created = await a.call('group.create', {
            name: '测试群' + stamp, memberIds: [String(bQc)]
        });
        const detail = created.detail;
        assert(detail && detail.id, '创建群聊缺少群 id');
        eq(detail.ownerId, String(aQc), '群主');
        assert(detail.members.some((member) => member.userId === String(bQc)), '成员缺少 B');
        const push = await b.waitFor('group.updated.push');
        eq(push.data.id, detail.id, '成员收到的群推送');
        const relogin = await login(b, nameB, '123456', 'device-b');
        assert(relogin.groups.some((group) => group.id === detail.id), 'B 的群列表没有新群');
    });
    await check('创建群聊：非好友不能被拉进群', async () => {
        let rejected = false;
        try {
            await a.call('group.create', { name: '非法群', memberIds: [String(cQc)] });
        } catch (error) {
            rejected = true;
        }
        assert(rejected, '非好友居然被拉进群');
    });
    await check('创建群聊：群名和成员校验', async () => {
        let emptyName = false;
        try {
            await a.call('group.create', { name: '   ', memberIds: [String(bQc)] });
        } catch (error) {
            emptyName = true;
        }
        let emptyMembers = false;
        try {
            await a.call('group.create', { name: '空群', memberIds: [] });
        } catch (error) {
            emptyMembers = true;
        }
        assert(emptyName, '空群名没有被拒绝');
        assert(emptyMembers, '空成员没有被拒绝');
    });
    // ---- 清理 ----
    a.close();
    b.close();
    if (c) c.close();

    console.log('\n== 结果 ==');
    console.log(`PASS ${passed} / FAIL ${failures.length}`);
    if (notes.length) console.log(notes.join('\n'));
    if (failures.length) {
        console.log('\n失败明细：');
        for (const failure of failures) console.log(' - ' + failure);
        process.exitCode = 1;
    }
}

main().catch((error) => {
    console.error('FATAL', error);
    process.exitCode = 2;
});
