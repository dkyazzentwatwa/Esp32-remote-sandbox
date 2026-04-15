#include <WiFi.h>
#include <WebServer.h>
#include <WebSocketsServer.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include <esp_system.h>
#include <time.h>

#define DEFAULT_AP_SSID     "ESP32Chat"
#define DEFAULT_AP_PASS     "chatroom1"
#define DEFAULT_MODE        "AP"      // AP | STA | AP_STA
#define DEFAULT_STA_SSID    ""
#define DEFAULT_STA_PASS    ""
#define DEFAULT_TZ          "UTC0"
#define DEFAULT_NTP1        "pool.ntp.org"
#define DEFAULT_NTP2        "time.nist.gov"

#define MAX_CLIENTS         8
#define MAX_HISTORY         50
#define RATE_LIMIT_MSGS     5
#define RATE_LIMIT_WINDOW   8000UL
#define MAX_MSG_LEN         480
#define MAX_NICK_LEN        20
#define MSG_TRACK_SIZE      64
#define NUM_ROOMS           2
#define MAX_FRAME_LEN       768
#define STA_CONNECT_MS      15000UL

char cfgApSSID[33]  = DEFAULT_AP_SSID;
char cfgApPass[65]  = DEFAULT_AP_PASS;
char cfgMode[12]    = DEFAULT_MODE;
char cfgStaSSID[33] = DEFAULT_STA_SSID;
char cfgStaPass[65] = DEFAULT_STA_PASS;
char cfgTZ[64]      = DEFAULT_TZ;
char cfgNtp1[64]    = DEFAULT_NTP1;
char cfgNtp2[64]    = DEFAULT_NTP2;

class MsgBuffer {
  String* buf;
  int cap, head, cnt;
public:
  explicit MsgBuffer(int size) : cap(size), head(0), cnt(0) {
    if (cap < 1) cap = 1;
    buf = new String[cap];
  }
  ~MsgBuffer() { delete[] buf; }

  void push(const String& json) {
    buf[head] = json;
    head = (head + 1) % cap;
    if (cnt < cap) cnt++;
  }

  int count() const { return cnt; }

  String toArray() const {
    String out;
    out.reserve((size_t)cnt * 96 + 2);
    out = "[";
    int start = (cnt < cap) ? 0 : head;
    for (int i = 0; i < cnt; i++) {
      if (i) out += ',';
      out += buf[(start + i) % cap];
    }
    out += "]";
    return out;
  }
};

struct Room { const char* name; MsgBuffer* history; };
struct Client {
  String nick;
  String room;
  bool active = false;
  uint32_t lastMsgMs = 0;
  int msgCount = 0;
};

struct MsgRecord {
  String id;
  String room;
  uint8_t sender = 0;
  bool valid = false;
};

Room rooms[NUM_ROOMS] = {{"room1", nullptr}, {"room2", nullptr}};
Client clients[MAX_CLIENTS];
MsgRecord msgTrack[MSG_TRACK_SIZE];
int trackHead = 0;

WebServer http(80);
WebSocketsServer ws(81);

bool ntpSynced = false;

String sanitize(const String& s, int maxLen = MAX_MSG_LEN) {
  String out;
  out.reserve((size_t)maxLen + 8);
  for (size_t i = 0; i < s.length(); i++) {
    if ((int)out.length() >= maxLen) break;
    const char c = s.charAt(i);
    switch (c) {
      case '<': out += F("&lt;"); break;
      case '>': out += F("&gt;"); break;
      case '&': out += F("&amp;"); break;
      case '"': out += F("&quot;"); break;
      default: if ((uint8_t)c >= 32) out += c;
    }
  }
  return out;
}

String getTS() {
  struct tm t;
  if (getLocalTime(&t, 50)) {
    char b[10];
    strftime(b, sizeof(b), "%H:%M:%S", &t);
    return String(b);
  }
  uint32_t s = millis() / 1000;
  char b[10];
  snprintf(b, sizeof(b), "%02lu:%02lu:%02lu", (unsigned long)((s / 3600) % 24),
           (unsigned long)((s / 60) % 60), (unsigned long)(s % 60));
  return String(b);
}

String getDateStr() {
  struct tm t;
  if (!getLocalTime(&t, 50)) return "";
  char b[12];
  strftime(b, sizeof(b), "%Y-%m-%d", &t);
  return String(b);
}

bool rateLimited(uint8_t num) {
  uint32_t now = millis();
  if (now - clients[num].lastMsgMs > RATE_LIMIT_WINDOW) {
    clients[num].msgCount = 0;
    clients[num].lastMsgMs = now;
  }
  if (clients[num].msgCount >= RATE_LIMIT_MSGS) return true;
  clients[num].msgCount++;
  return false;
}

int roomIdx(const String& name) {
  for (int i = 0; i < NUM_ROOMS; i++) if (name.equals(rooms[i].name)) return i;
  return -1;
}

bool roomMembershipValid(uint8_t sender, const String& room) {
  return sender < MAX_CLIENTS && clients[sender].active && clients[sender].room == room;
}

void sendErr(uint8_t num, const __FlashStringHelper* text) {
  StaticJsonDocument<128> doc;
  doc["type"] = "err";
  doc["text"] = text;
  String json;
  serializeJson(doc, json);
  ws.sendTXT(num, json);
}

void broadcastRoom(const String& room, const String& json) {
  for (uint8_t i = 0; i < MAX_CLIENTS; i++)
    if (clients[i].active && clients[i].room == room) ws.sendTXT(i, json);
}

void broadcastRoomExcept(const String& room, uint8_t skip, const String& json) {
  for (uint8_t i = 0; i < MAX_CLIENTS; i++)
    if (clients[i].active && clients[i].room == room && i != skip) ws.sendTXT(i, json);
}

void broadcastUsers(const String& room) {
  StaticJsonDocument<600> doc;
  doc["type"] = "users";
  doc["room"] = room;
  JsonArray arr = doc.createNestedArray("users");
  for (uint8_t i = 0; i < MAX_CLIENTS; i++)
    if (clients[i].active && clients[i].room == room) arr.add(clients[i].nick);
  String json; serializeJson(doc, json);
  broadcastRoom(room, json);
}

String buildMsg(const String& id, const String& room, const String& nick,
                const String& text, const String& ts, const String& date) {
  StaticJsonDocument<640> doc;
  doc["type"] = "msg";
  doc["id"] = id;
  doc["room"] = room;
  doc["nick"] = nick;
  doc["text"] = text;
  doc["ts"] = ts;
  if (date.length()) doc["date"] = date;
  String json; serializeJson(doc, json);
  return json;
}

String buildSys(const String& room, const String& text) {
  StaticJsonDocument<256> doc;
  doc["type"] = "sys";
  doc["room"] = room;
  doc["text"] = text;
  String json; serializeJson(doc, json);
  return json;
}

void trackMsg(const String& id, uint8_t sender, const String& room) {
  msgTrack[trackHead].id = id;
  msgTrack[trackHead].sender = sender;
  msgTrack[trackHead].room = room;
  msgTrack[trackHead].valid = true;
  trackHead = (trackHead + 1) % MSG_TRACK_SIZE;
}

int8_t findSenderInRoom(const String& id, const String& room) {
  for (int i = 0; i < MSG_TRACK_SIZE; i++)
    if (msgTrack[i].valid && msgTrack[i].id == id && msgTrack[i].room == room) return (int8_t)msgTrack[i].sender;
  return -1;
}

void sendHistory(uint8_t num, int ridx) {
  if (ridx < 0 || ridx >= NUM_ROOMS || rooms[ridx].history == nullptr) return;
  String json;
  json.reserve(4096);
  json = "{\"type\":\"history\",\"room\":\"";
  json += rooms[ridx].name;
  json += "\",\"messages\":";
  json += rooms[ridx].history->toArray();
  json += "}";
  ws.sendTXT(num, json);
}

void handleTextFrame(uint8_t num, uint8_t* payload, size_t len) {
  if (len == 0 || len > MAX_FRAME_LEN) return;
  String msg;
  msg.reserve(len + 1);
  for (size_t i = 0; i < len; i++) msg += (char)payload[i];
  msg.trim();
  if (!msg.length()) return;

  if (msg.startsWith("JOIN:")) {
    int c = msg.indexOf(':', 5);
    if (c < 0) return;
    String room = msg.substring(5, c); room.trim();
    String nick = msg.substring(c + 1); nick.trim();
    if (roomIdx(room) < 0) { sendErr(num, F("Invalid room")); return; }
    if (!nick.length()) nick = "anon";
    nick = sanitize(nick.substring(0, MAX_NICK_LEN), MAX_NICK_LEN);

    String oldRoom = clients[num].room;
    String oldNick = clients[num].nick.length() ? clients[num].nick : nick;
    if (oldRoom.length() && oldRoom != room) {
      broadcastRoom(oldRoom, buildSys(oldRoom, oldNick + " left"));
      broadcastUsers(oldRoom);
    }

    clients[num].active = true;
    clients[num].nick = nick;
    clients[num].room = room;

    int ridx = roomIdx(room);
    sendHistory(num, ridx);
    broadcastRoom(room, buildSys(room, nick + " joined"));
    broadcastUsers(room);
    return;
  }

  if (!clients[num].active) return;

  if (msg.startsWith("MSG:")) {
    int c = msg.indexOf(':', 4);
    if (c < 0) return;
    String room = msg.substring(4, c);
    String text = msg.substring(c + 1);
    if (!roomMembershipValid(num, room) || !text.length()) return;
    if (rateLimited(num)) { sendErr(num, F("Slow down")); return; }

    text = sanitize(text, MAX_MSG_LEN);
    if (!text.length()) return;

    String id = String(millis()) + "_" + String(num) + "_" + String((uint32_t)esp_random(), HEX);
    String json = buildMsg(id, room, clients[num].nick, text, getTS(), getDateStr());
    int ridx = roomIdx(room);
    if (ridx >= 0 && rooms[ridx].history) rooms[ridx].history->push(json);
    trackMsg(id, num, room);
    broadcastRoom(room, json);
    return;
  }

  if (msg.startsWith("TYPING:")) {
    int c = msg.indexOf(':', 7);
    if (c < 0) return;
    String room = msg.substring(7, c);
    if (!roomMembershipValid(num, room)) return;
    StaticJsonDocument<180> doc;
    doc["type"] = "typing";
    doc["room"] = room;
    doc["nick"] = clients[num].nick;
    doc["active"] = (msg.substring(c + 1) == "1");
    String json; serializeJson(doc, json);
    broadcastRoomExcept(room, num, json);
    return;
  }

  if (msg.startsWith("SEEN:")) {
    String id = msg.substring(5); id.trim();
    int8_t sender = findSenderInRoom(id, clients[num].room);
    if (sender >= 0 && sender != (int8_t)num && clients[sender].active && clients[sender].room == clients[num].room) {
      StaticJsonDocument<180> doc;
      doc["type"] = "seen";
      doc["id"] = id;
      doc["nick"] = clients[num].nick;
      String json; serializeJson(doc, json);
      ws.sendTXT((uint8_t)sender, json);
    }
    return;
  }

  if (msg.startsWith("REACT:")) {
    int c = msg.indexOf(':', 6);
    if (c < 0) return;
    String id = msg.substring(6, c);
    String emoji = msg.substring(c + 1); emoji.trim();
    if (!id.length() || !emoji.length() || emoji.length() > 8) return;

    StaticJsonDocument<220> doc;
    doc["type"] = "react";
    doc["id"] = id;
    doc["emoji"] = emoji;
    doc["nick"] = clients[num].nick;
    String json; serializeJson(doc, json);
    broadcastRoom(clients[num].room, json);
    return;
  }

  if (msg.startsWith("NICK:")) {
    String nn = msg.substring(5); nn.trim();
    if (!nn.length()) return;
    nn = sanitize(nn.substring(0, MAX_NICK_LEN), MAX_NICK_LEN);
    String old = clients[num].nick;
    clients[num].nick = nn;
    String room = clients[num].room;
    broadcastRoom(room, buildSys(room, old + " is now known as " + nn));
    broadcastUsers(room);
  }
}

void onWsEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t len) {
  if (num >= MAX_CLIENTS) return;
  switch (type) {
    case WStype_DISCONNECTED: {
      if (!clients[num].active) break;
      String room = clients[num].room;
      String nick = clients[num].nick;
      clients[num] = Client();
      if (room.length()) {
        broadcastRoom(room, buildSys(room, nick + " left"));
        broadcastUsers(room);
      }
      break;
    }
    case WStype_TEXT: handleTextFrame(num, payload, len); break;
    default: break;
  }
}

const char HTML[] PROGMEM = R"rawhtml(<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black">
<title>ESP32 Chat</title>
<style>
:root{
  --bg:#0b141a;--header:#202c33;--panel:#111b21;
  --bubble-out:#005c4b;--bubble-in:#202c33;
  --input-bg:#2a3942;--green:#00a884;--text:#e9edef;
  --text2:#8696a0;--tick-blue:#53bdeb;--border:#2a3942;
}
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
html,body{height:100%;overflow:hidden}
body{font-family:-apple-system,system-ui,'Segoe UI',sans-serif;background:var(--bg);color:var(--text);display:flex;flex-direction:column;height:100dvh}
#sbar{background:var(--header);text-align:center;font-size:11px;color:var(--text2);padding:3px 8px;flex-shrink:0;border-bottom:1px solid var(--border)}
#hdr{background:var(--header);padding:0 14px;display:flex;align-items:center;justify-content:space-between;flex-shrink:0;height:54px;border-bottom:1px solid var(--border)}
#tabs{display:flex;gap:6px}
.tab{background:none;border:1px solid var(--border);border-radius:20px;color:var(--text2);padding:5px 14px;font-size:13px;cursor:pointer;transition:.2s}
.tab.on{background:var(--green);border-color:var(--green);color:#fff;font-weight:600}
#hdr-r{display:flex;align-items:center;gap:10px}
#badge{font-size:12px;color:var(--text2)}
.hbtn{background:none;border:none;color:var(--text2);cursor:pointer;padding:6px;border-radius:50%;display:flex;align-items:center;justify-content:center;transition:.15s}
.hbtn:hover,.hbtn:active{background:rgba(255,255,255,.08)}
#upanel{position:fixed;top:82px;right:0;width:230px;height:calc(100dvh - 82px);background:var(--panel);border-left:1px solid var(--border);z-index:60;transform:translateX(100%);transition:transform .25s ease;overflow-y:auto}
#upanel.open{transform:translateX(0)}
#uphdr{padding:14px 16px;font-size:12px;font-weight:700;color:var(--text2);text-transform:uppercase;letter-spacing:1px;border-bottom:1px solid var(--border)}
.uitem{display:flex;align-items:center;gap:10px;padding:10px 14px}
.uav{width:34px;height:34px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:700;color:#fff;flex-shrink:0;position:relative}
.udot{position:absolute;bottom:0;right:0;width:9px;height:9px;border-radius:50%;background:var(--green);border:2px solid var(--panel)}
.unick{font-size:13.5px}
#mwrap{flex:1;position:relative;overflow:hidden;min-height:0}
#msgs{height:100%;overflow-y:auto;padding:6px 3% 4px;display:flex;flex-direction:column;gap:0}
#msgs::-webkit-scrollbar{width:4px}
#msgs::-webkit-scrollbar-thumb{background:var(--border);border-radius:2px}
.dsep{display:flex;justify-content:center;margin:10px 0 4px}
.dsep span{background:rgba(11,20,26,.92);border:1px solid var(--border);border-radius:8px;padding:4px 12px;font-size:11.5px;color:var(--text2)}
.mrow{display:flex;align-items:flex-end;gap:5px;max-width:100%}
.mrow.sent{flex-direction:row-reverse}
.mrow.recv{flex-direction:row}
.mrow.gap{margin-top:8px}
.mrow.tight{margin-top:1px}
.av{width:26px;height:26px;border-radius:50%;flex-shrink:0;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;color:#fff}
.av.ghost{visibility:hidden}
.mrow.sent .av{display:none}
.bub{position:relative;max-width:min(78vw,370px);padding:6px 10px 4px;border-radius:8px;word-break:break-word;cursor:default}
.mrow.sent .bub{background:var(--bubble-out);border-bottom-right-radius:2px}
.mrow.recv .bub{background:var(--bubble-in);border-bottom-left-radius:2px}
.mrow.tight.sent .bub{border-radius:8px;border-bottom-right-radius:2px}
.mrow.tight.recv .bub{border-radius:8px;border-bottom-left-radius:2px}
.mrow.gap.sent .bub::after{content:'';position:absolute;right:-8px;bottom:0;border-left:8px solid var(--bubble-out);border-top:8px solid transparent}
.mrow.gap.recv .bub::after{content:'';position:absolute;left:-8px;bottom:0;border-right:8px solid var(--bubble-in);border-top:8px solid transparent}
.sname{font-size:12.5px;font-weight:600;margin-bottom:2px}
.mtxt{font-size:14.5px;line-height:1.45;white-space:pre-wrap}
.mmeta{display:flex;align-items:center;gap:3px;float:right;margin-left:10px;margin-top:2px}
.mtime{font-size:11px;color:rgba(233,237,239,.55);white-space:nowrap}
.ticks{font-size:14px;line-height:1;color:rgba(233,237,239,.55)}
.ticks.seen{color:var(--tick-blue)}
.reacts{display:flex;flex-wrap:wrap;gap:3px;margin-top:4px;clear:both}
.rpill{background:rgba(0,0,0,.3);border:1px solid rgba(255,255,255,.1);border-radius:14px;padding:2px 7px;font-size:12.5px;cursor:pointer;display:flex;align-items:center;gap:3px;transition:.15s}
.rpill:hover{background:rgba(255,255,255,.12)}
.rcount{font-size:11px;color:var(--text2)}
.sysmsg{align-self:center;background:rgba(11,20,26,.85);border:1px solid var(--border);border-radius:8px;padding:4px 14px;font-size:12px;color:var(--text2);margin:4px 0;text-align:center}
#tbar{min-height:22px;padding:0 14px 2px;flex-shrink:0;display:flex;align-items:center;gap:7px;font-size:12px;color:var(--text2)}
.tdots{display:flex;gap:3px;align-items:center}
.tdots span{width:5px;height:5px;border-radius:50%;background:var(--green);animation:bounce 1.1s infinite}
.tdots span:nth-child(2){animation-delay:.18s}
.tdots span:nth-child(3){animation-delay:.36s}
@keyframes bounce{0%,60%,100%{transform:translateY(0)}30%{transform:translateY(-5px)}}
#scrbtn{position:absolute;bottom:10px;right:10px;width:40px;height:40px;border-radius:50%;background:var(--header);border:1px solid var(--border);color:var(--text2);font-size:18px;cursor:pointer;display:flex;align-items:center;justify-content:center;z-index:10;box-shadow:0 2px 10px rgba(0,0,0,.45);transition:opacity .2s}
#scrbtn.hide{opacity:0;pointer-events:none}
#scrbdg{position:absolute;top:-6px;right:-6px;background:var(--green);color:#fff;font-size:10px;font-weight:700;min-width:18px;height:18px;border-radius:9px;display:flex;align-items:center;justify-content:center;padding:0 4px}
#scrbdg.hide{display:none}
#rpicker{position:fixed;background:var(--header);border-radius:28px;padding:7px 12px;display:flex;gap:2px;z-index:100;box-shadow:0 4px 24px rgba(0,0,0,.55);animation:popIn .15s ease}
#rpicker.hide{display:none}
@keyframes popIn{from{opacity:0;transform:scale(.75)}to{opacity:1;transform:scale(1)}}
.remoji{font-size:24px;cursor:pointer;padding:4px 6px;border-radius:50%;border:none;background:none;transition:transform .12s}
.remoji:hover,.remoji:active{transform:scale(1.35)}
#iarea{background:var(--panel);padding:8px 10px;display:flex;align-items:flex-end;gap:8px;flex-shrink:0;border-top:1px solid var(--border)}
#minput{flex:1;background:var(--input-bg);border:none;border-radius:24px;padding:10px 16px;color:var(--text);font-size:15px;outline:none;resize:none;font-family:inherit;max-height:120px;overflow-y:auto;line-height:1.4}
#minput::placeholder{color:var(--text2)}
.ibtn{width:42px;height:42px;border-radius:50%;border:none;background:none;color:var(--text2);cursor:pointer;display:flex;align-items:center;justify-content:center;flex-shrink:0;transition:.15s}
.ibtn:hover,.ibtn:active{background:rgba(255,255,255,.08)}
#sendbtn{background:var(--green)!important;color:#fff!important}
#sendbtn:hover,#sendbtn:active{background:#00cf9d!important}
</style>
</head>
<body>
<div id="sbar">connecting…</div>
<div id="hdr">
  <div id="tabs">
    <button class="tab on" id="t-room1" onclick="switchRoom('room1')">💬 Room 1</button>
    <button class="tab" id="t-room2" onclick="switchRoom('room2')">💬 Room 2</button>
  </div>
  <div id="hdr-r"><span id="badge">0 online</span><button class="hbtn" onclick="toggleUsers()" title="Users">👥</button></div>
</div>
<div id="upanel"><div id="uphdr">Online Users</div><div id="ulist"></div></div>
<div id="mwrap"><div id="msgs"></div><button id="scrbtn" class="hide" onclick="scrollBot()">↓<span id="scrbdg" class="hide">0</span></button></div>
<div id="tbar"></div>
<div id="rpicker" class="hide">
  <button class="remoji" onclick="sendReact('👍')">👍</button><button class="remoji" onclick="sendReact('❤️')">❤️</button>
  <button class="remoji" onclick="sendReact('😂')">😂</button><button class="remoji" onclick="sendReact('😮')">😮</button>
  <button class="remoji" onclick="sendReact('😢')">😢</button><button class="remoji" onclick="sendReact('🙏')">🙏</button>
</div>
<div id="iarea">
  <button class="ibtn" onclick="insertEmoji()" title="Emoji">😊</button>
  <textarea id="minput" rows="1" placeholder="Message" maxlength="480"></textarea>
  <button class="ibtn" id="sendbtn" onclick="sendMsg()" title="Send">➤</button>
</div>
<script>
let myNick = localStorage.getItem('esp_nick') || '';
let curRoom = 'room1';
let ws;
let connected = false;
let rDelay = 1000;
const S = {
  room1: { messages:[], typing:{}, users:[], unread:0, lastDate:null },
  room2: { messages:[], typing:{}, users:[], unread:0, lastDate:null }
};
const pending = [];
const colorMap = {};
let reactTarget = null;
let typingOut = false, typingTmr;
const palette = ['#e91e63','#9c27b0','#673ab7','#3f51b5','#2196f3','#0097a7','#00897b','#43a047','#fb8c00','#e53935','#6d4c41','#546e7a'];
function nickColor(n){if(colorMap[n])return colorMap[n];let h=5381;for(let i=0;i<n.length;i++)h=Math.imul(h,31)+n.charCodeAt(i)|0;return(colorMap[n]=palette[Math.abs(h)%palette.length])}
function avi(n){return n?n[0].toUpperCase():'?'}
const $msgs=()=>document.getElementById('msgs');
const atBot=()=>{const e=$msgs();return e.scrollHeight-e.scrollTop-e.clientHeight<50}
function setStatus(t){document.getElementById('sbar').textContent=t}
function scrollBot(){$msgs().scrollTo({top:1e9,behavior:'smooth'});S[curRoom].unread=0;refreshScrollBtn()}
function refreshScrollBtn(){const u=S[curRoom].unread;document.getElementById('scrbtn').classList.toggle('hide',atBot());const b=document.getElementById('scrbdg');if(u>0){b.textContent=u>99?'99+':u;b.classList.remove('hide')}else b.classList.add('hide')}
$msgs().addEventListener('scroll',()=>{if(atBot()){S[curRoom].unread=0;refreshScrollBtn();markSeen()}else refreshScrollBtn()});
function markSeen(){if(!connected)return;document.querySelectorAll('.mrow.recv[data-id]').forEach(r=>{if(r.dataset.id)ws.send('SEEN:'+r.dataset.id)})}
function refreshTyping(){const names=Object.keys(S[curRoom].typing).filter(k=>S[curRoom].typing[k]);const bar=document.getElementById('tbar');if(!names.length){bar.innerHTML='';return;}const lbl=names.length===1?names[0]+' is typing':names.length===2?names[0]+' and '+names[1]+' are typing':names.length+' people are typing';bar.innerHTML=`<span style="color:var(--green);font-size:12px">${lbl}</span><div class="tdots"><span></span><span></span><span></span></div>`}
function renderUsers(users){document.getElementById('badge').textContent=users.length+' online';const ul=document.getElementById('ulist');ul.innerHTML='';users.forEach(n=>{const d=document.createElement('div');d.className='uitem';d.innerHTML=`<div class="uav" style="background:${nickColor(n)}">${avi(n)}<div class="udot"></div></div><span class="unick">${n}${n===myNick?' <span style="color:var(--text2);font-size:11px">(you)</span>':''}</span>`;ul.appendChild(d)})}
function toggleUsers(){document.getElementById('upanel').classList.toggle('open')}
document.addEventListener('click',e=>{const p=document.getElementById('upanel');if(!p.contains(e.target)&&!e.target.closest('.hbtn'))p.classList.remove('open')})
function showPicker(msgId,b){reactTarget=msgId;const p=document.getElementById('rpicker');p.classList.remove('hide');const r=b.getBoundingClientRect();const pw=p.offsetWidth||260;const left=Math.max(6,Math.min(r.left,window.innerWidth-pw-6));p.style.left=left+'px';p.style.top=(r.top-64+window.scrollY)+'px'}
function hidePicker(){document.getElementById('rpicker').classList.add('hide');reactTarget=null}
function sendReact(emoji){if(!reactTarget||!connected)return;ws.send('REACT:'+reactTarget+':'+emoji);hidePicker()}
document.addEventListener('click',e=>{if(!document.getElementById('rpicker').classList.contains('hide')&&!e.target.closest('#rpicker')&&!e.target.closest('.bub'))hidePicker()})
function updateReact(msgId,emoji){const box=document.getElementById('rx-'+msgId);if(!box)return;let pill=box.querySelector(`[data-e="${emoji}"]`);if(!pill){pill=document.createElement('div');pill.className='rpill';pill.dataset.e=emoji;pill.dataset.c=1;pill.innerHTML=emoji+' <span class="rcount">1</span>';pill.onclick=()=>sendReact(emoji);box.appendChild(pill);return;}const c=(parseInt(pill.dataset.c)||0)+1;pill.dataset.c=c;pill.querySelector('.rcount').textContent=c}
function dateLabel(d){if(!d)return null;const now=new Date(),yest=new Date(now-864e5),td=new Date(d);if(td.toDateString()===now.toDateString())return 'Today';if(td.toDateString()===yest.toDateString())return 'Yesterday';return td.toLocaleDateString(undefined,{weekday:'long',month:'short',day:'numeric'})}
function renderMsg(m){const container=$msgs();const isMine=m.nick===myNick;if(m.date){const rs=S[curRoom];if(rs.lastDate!==m.date){rs.lastDate=m.date;const sep=document.createElement('div');sep.className='dsep';sep.innerHTML=`<span>${dateLabel(m.date)||m.date}</span>`;container.appendChild(sep)}}const rows=container.querySelectorAll('.mrow');const ref=rows[rows.length-1];const consec=ref&&ref.dataset.nick===m.nick;const row=document.createElement('div');row.className='mrow '+(isMine?'sent':'recv')+' '+(consec?'tight':'gap');row.dataset.nick=m.nick;row.dataset.id=m.id||'';if(!isMine){const av=document.createElement('div');av.className='av'+(consec?' ghost':'');if(!consec){av.style.background=nickColor(m.nick);av.textContent=avi(m.nick)}row.appendChild(av)}const bub=document.createElement('div');bub.className='bub';if(!isMine&&!consec){const sn=document.createElement('div');sn.className='sname';sn.style.color=nickColor(m.nick);sn.textContent=m.nick;bub.appendChild(sn)}const txt=document.createElement('div');txt.className='mtxt';txt.textContent=m.text;bub.appendChild(txt);const meta=document.createElement('div');meta.className='mmeta';const ts=document.createElement('span');ts.className='mtime';ts.textContent=(m.ts||'').substring(0,5);meta.appendChild(ts);if(isMine){const tk=document.createElement('span');tk.className='ticks';tk.id='tk-'+m.id;tk.textContent=m.pending?'🕐':'✓';meta.appendChild(tk)}bub.appendChild(meta);const rx=document.createElement('div');rx.className='reacts';rx.id='rx-'+m.id;bub.appendChild(rx);let pt;bub.addEventListener('touchstart',()=>{pt=setTimeout(()=>showPicker(m.id,bub),550)},{passive:true});bub.addEventListener('touchend',()=>clearTimeout(pt));bub.addEventListener('touchmove',()=>clearTimeout(pt));bub.addEventListener('contextmenu',e=>{e.preventDefault();showPicker(m.id,bub)});row.appendChild(bub);container.appendChild(row);return row}
function renderSys(text,room){if(room!==curRoom)return;const el=document.createElement('div');el.className='sysmsg';el.textContent=text;$msgs().appendChild(el)}
function switchRoom(room){if(room===curRoom)return;curRoom=room;document.querySelectorAll('.tab').forEach(t=>t.classList.remove('on'));document.getElementById('t-'+room).classList.add('on');$msgs().innerHTML='';S[room].lastDate=null;S[room].messages.forEach(m=>m.sys?renderSys(m.text,room):renderMsg(m));renderUsers(S[room].users);S[room].unread=0;refreshScrollBtn();refreshTyping();if(connected)ws.send('JOIN:'+room+':'+myNick);setTimeout(scrollBot,60)}
function sendMsg(){const inp=document.getElementById('minput');const txt=inp.value.trim();if(!txt)return;if(txt.startsWith('/')){const parts=txt.split(' '),cmd=parts[0].toLowerCase();inp.value='';resize(inp);if(cmd==='/nick'){const nn=parts.slice(1).join(' ').trim();if(nn){myNick=nn.substring(0,20);localStorage.setItem('esp_nick',myNick);if(connected)ws.send('NICK:'+myNick);setStatus('Nick → '+myNick)}}else if(cmd==='/clear'){$msgs().innerHTML='';S[curRoom].messages=[];}else if(cmd==='/help'){renderSys('/nick <name>  /clear  /help  /users',curRoom)}else if(cmd==='/users'){toggleUsers()}else renderSys('Unknown command. Try /help',curRoom);return;}if(!connected){setStatus('Not connected');return;}const pm={id:'p_'+Date.now(),nick:myNick,text:txt,ts:new Date().toTimeString().substring(0,8),pending:true};const el=renderMsg(pm);pending.push(el);ws.send('MSG:'+curRoom+':'+txt);inp.value='';resize(inp);if(typingOut){typingOut=false;clearTimeout(typingTmr);ws.send('TYPING:'+curRoom+':0')}setTimeout(scrollBot,50)}
function insertEmoji(){const e=['😊','👍','❤️','😂','🔥','🎉','✅','🙌'][Math.random()*8|0];const inp=document.getElementById('minput');inp.value+=e;inp.focus();resize(inp)}
function resize(el){el.style.height='auto';el.style.height=Math.min(el.scrollHeight,120)+'px'}
document.getElementById('minput').addEventListener('input',function(){resize(this);if(!connected)return;if(!typingOut){typingOut=true;ws.send('TYPING:'+curRoom+':1')}clearTimeout(typingTmr);typingTmr=setTimeout(()=>{typingOut=false;if(connected)ws.send('TYPING:'+curRoom+':0')},2500)});
document.getElementById('minput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();sendMsg()}})
function connect(){ws=new WebSocket('ws://'+location.hostname+':81');ws.onopen=()=>{connected=true;rDelay=1000;setStatus('connected as '+myNick);ws.send('JOIN:'+curRoom+':'+myNick)};ws.onclose=()=>{connected=false;setStatus('reconnecting…');setTimeout(connect,rDelay);rDelay=Math.min(rDelay*1.5,12000)};ws.onerror=()=>ws.close();ws.onmessage=e=>{let d;try{d=JSON.parse(e.data)}catch(_){return}handle(d)}}
function handle(d){switch(d.type){case'msg':{const mine=d.nick===myNick,cur=d.room===curRoom;S[d.room].messages.push(d);if(S[d.room].messages.length>100)S[d.room].messages.shift();if(cur){const bot=atBot();if(mine&&pending.length){const el=pending.shift();if(el&&el.parentNode){el.dataset.id=d.id;const tk=el.querySelector('.ticks');if(tk){tk.textContent='✓✓';tk.id='tk-'+d.id}const rx=el.querySelector('.reacts');if(rx)rx.id='rx-'+d.id;}}else{renderMsg(d);if(!mine){if(bot&&connected)ws.send('SEEN:'+d.id);else{S[d.room].unread++;refreshScrollBtn();}}}if(bot)setTimeout(scrollBot,40)}else if(!mine){S[d.room].unread++;}break;}case'history':{S[d.room].messages=d.messages||[];if(d.room===curRoom){$msgs().innerHTML='';S[curRoom].lastDate=null;S[d.room].messages.forEach(m=>renderMsg(m));setTimeout(scrollBot,60)}break;}case'sys':{S[d.room].messages.push({sys:true,text:d.text});if(d.room===curRoom)renderSys(d.text,d.room);break;}case'users':{S[d.room].users=d.users||[];if(d.room===curRoom)renderUsers(d.users||[]);break;}case'typing':{if(d.room!==curRoom||d.nick===myNick)break;if(d.active)S[curRoom].typing[d.nick]=true;else delete S[curRoom].typing[d.nick];refreshTyping();break;}case'seen':{const tk=document.getElementById('tk-'+d.id);if(tk){tk.textContent='✓✓';tk.className='ticks seen'}break;}case'react':updateReact(d.id,d.emoji);break;case'err':setStatus('⚠ '+d.text);break;}}
(function(){if(!myNick){myNick=(prompt('Enter your name:')||'anon').trim().substring(0,20)||'anon';localStorage.setItem('esp_nick',myNick);}connect();})();
</script>
</body>
</html>)rawhtml";

void sendCommonHeaders() {
  http.sendHeader("Cache-Control", "no-store, max-age=0");
  http.sendHeader("X-Content-Type-Options", "nosniff");
  http.sendHeader("X-Frame-Options", "DENY");
  http.sendHeader("Referrer-Policy", "no-referrer");
}

void handleRoot() {
  sendCommonHeaders();
  http.send_P(200, "text/html; charset=utf-8", HTML);
}

void handleStatus() {
  StaticJsonDocument<768> doc;
  doc["heap_free"] = ESP.getFreeHeap();
  doc["heap_total"] = ESP.getHeapSize();
  doc["uptime_sec"] = millis() / 1000;
  doc["ntp_synced"] = ntpSynced;
  doc["wifi_mode"] = (int)WiFi.getMode();
  doc["mode_cfg"] = cfgMode;
  doc["ap_ip"] = WiFi.softAPIP().toString();
  doc["sta_ip"] = WiFi.localIP().toString();

  JsonObject roomsObj = doc.createNestedObject("rooms");
  for (int r = 0; r < NUM_ROOMS; r++) {
    JsonObject ro = roomsObj.createNestedObject(rooms[r].name);
    int online = 0;
    for (uint8_t i = 0; i < MAX_CLIENTS; i++)
      if (clients[i].active && clients[i].room == String(rooms[r].name)) online++;
    ro["online"] = online;
    ro["history"] = rooms[r].history ? rooms[r].history->count() : 0;
  }

  String json; serializeJson(doc, json);
  sendCommonHeaders();
  http.send(200, "application/json", json);
}

void handleNotFound() {
  sendCommonHeaders();
  http.sendHeader("Location", "/");
  http.send(302);
}

void loadConfig() {
  if (!LittleFS.begin(true)) {
    Serial.println(F("[CFG] LittleFS mount failed — using defaults"));
    return;
  }
  if (!LittleFS.exists("/config.json")) {
    Serial.println(F("[CFG] No config.json — using defaults"));
    return;
  }

  File f = LittleFS.open("/config.json", "r");
  if (!f) return;

  StaticJsonDocument<768> doc;
  DeserializationError err = deserializeJson(doc, f);
  f.close();
  if (err) {
    Serial.printf("[CFG] config.json parse failed: %s\n", err.c_str());
    return;
  }

  if (doc["ap_ssid"].is<const char*>()) strlcpy(cfgApSSID, doc["ap_ssid"], sizeof(cfgApSSID));
  if (doc["ap_pass"].is<const char*>()) strlcpy(cfgApPass, doc["ap_pass"], sizeof(cfgApPass));
  if (doc["mode"].is<const char*>()) strlcpy(cfgMode, doc["mode"], sizeof(cfgMode));
  if (doc["sta_ssid"].is<const char*>()) strlcpy(cfgStaSSID, doc["sta_ssid"], sizeof(cfgStaSSID));
  if (doc["sta_pass"].is<const char*>()) strlcpy(cfgStaPass, doc["sta_pass"], sizeof(cfgStaPass));
  if (doc["tz"].is<const char*>()) strlcpy(cfgTZ, doc["tz"], sizeof(cfgTZ));
  if (doc["ntp1"].is<const char*>()) strlcpy(cfgNtp1, doc["ntp1"], sizeof(cfgNtp1));
  if (doc["ntp2"].is<const char*>()) strlcpy(cfgNtp2, doc["ntp2"], sizeof(cfgNtp2));

  Serial.printf("[CFG] mode=%s ap=%s sta=%s tz=%s\n", cfgMode, cfgApSSID, cfgStaSSID, cfgTZ);
}

void setupTimeSync() {
  setenv("TZ", cfgTZ, 1);
  tzset();
  configTime(0, 0, cfgNtp1, cfgNtp2);

  struct tm t;
  ntpSynced = getLocalTime(&t, 4000);
  Serial.printf("[NTP] synced=%s via %s,%s tz=%s\n", ntpSynced ? "yes" : "no", cfgNtp1, cfgNtp2, cfgTZ);
}

bool startSoftAP() {
  if (strlen(cfgApPass) < 8) strlcpy(cfgApPass, DEFAULT_AP_PASS, sizeof(cfgApPass));
  bool ok = WiFi.softAP(cfgApSSID, cfgApPass);
  if (!ok) {
    Serial.println(F("[WIFI] softAP failed, using defaults"));
    ok = WiFi.softAP(DEFAULT_AP_SSID, DEFAULT_AP_PASS);
  }
  Serial.printf("[WIFI] AP %s | IP %s\n", cfgApSSID, WiFi.softAPIP().toString().c_str());
  return ok;
}

bool connectSTA() {
  if (!strlen(cfgStaSSID)) return false;
  WiFi.begin(cfgStaSSID, cfgStaPass);
  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED && (millis() - start) < STA_CONNECT_MS) {
    delay(250);
  }
  bool ok = WiFi.status() == WL_CONNECTED;
  Serial.printf("[WIFI] STA %s | IP %s\n", ok ? "connected" : "failed", WiFi.localIP().toString().c_str());
  return ok;
}

void setupNetworking() {
  WiFi.setSleep(false);

  String mode = String(cfgMode);
  mode.toUpperCase();

  if (mode == "STA") {
    WiFi.mode(WIFI_STA);
    if (!connectSTA()) {
      Serial.println(F("[WIFI] STA failed; fallback to AP"));
      WiFi.mode(WIFI_AP);
      startSoftAP();
    }
    return;
  }

  if (mode == "AP_STA") {
    WiFi.mode(WIFI_AP_STA);
    startSoftAP();
    connectSTA();
    return;
  }

  WiFi.mode(WIFI_AP);
  startSoftAP();
}

void setup() {
  Serial.begin(115200);
  delay(100);
  Serial.println(F("\n[BOOT] ESP32 Chat starting..."));

  loadConfig();

  for (int i = 0; i < NUM_ROOMS; i++) rooms[i].history = new MsgBuffer(MAX_HISTORY);

  setupNetworking();
  setupTimeSync();

  http.on("/", HTTP_GET, handleRoot);
  http.on("/status", HTTP_GET, handleStatus);
  http.onNotFound(handleNotFound);
  http.begin();

  ws.begin();
  ws.onEvent(onWsEvent);

  String host = WiFi.getMode() == WIFI_STA ? WiFi.localIP().toString() : WiFi.softAPIP().toString();
  Serial.printf("[READY] Open http://%s\n", host.c_str());
}

void loop() {
  http.handleClient();
  ws.loop();
  delay(2);
}
