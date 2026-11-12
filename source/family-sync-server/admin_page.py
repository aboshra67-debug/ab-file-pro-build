"""The AB File Pro admin site (one self-contained page; every call goes to /v1/admin/* with the admin token)."""

ADMIN_PAGE = r"""<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex,nofollow">
<meta name="theme-color" content="#2563eb">
<title>AB File Pro — الإدارة</title>
<style>
:root{--bg:#f4f6fb;--card:#fff;--ink:#14203a;--mut:#5f6b82;--line:#dfe4ee;--pri:#2563eb;--pri2:#e6eeff;--bad:#c0392b;--ok:#17804a;--warn:#b26a00;--shadow:0 1px 2px rgba(20,32,58,.06),0 6px 18px rgba(20,32,58,.05)}
@media (prefers-color-scheme:dark){:root{--bg:#0e1218;--card:#171d26;--ink:#e8edf6;--mut:#9aa6bb;--line:#273040;--pri:#6ea0ff;--pri2:#1c2a44;--bad:#ff8f80;--ok:#5fd39a;--warn:#f2b45a;--shadow:none}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0}body{background:var(--bg);color:var(--ink);font:16px/1.55 system-ui,"Segoe UI",Tahoma,sans-serif;padding-bottom:84px}
header{position:sticky;top:0;z-index:5;background:var(--card);border-bottom:1px solid var(--line);padding:12px 16px;padding-top:max(12px,env(safe-area-inset-top));display:flex;align-items:center;gap:10px}
header h1{font-size:17px;margin:0;flex:1}header .logo{width:32px;height:32px;border-radius:10px;background:var(--pri);color:#fff;display:grid;place-items:center;font-weight:800;font-size:13px}
main{max-width:640px;margin:0 auto;padding:14px 16px;display:grid;gap:14px}
.card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:14px;box-shadow:var(--shadow)}
h2{font-size:15px;margin:0 0 10px}label{display:block;font-size:13px;color:var(--mut);margin:10px 0 4px}
input,select,textarea,button{font:inherit;color:inherit}
input,select{width:100%;padding:11px 12px;border-radius:13px;border:1px solid var(--line);background:var(--bg)}
button{border:0;border-radius:13px;padding:11px 14px;font-weight:700;background:var(--pri);color:#fff;cursor:pointer}
button.alt{background:var(--pri2);color:var(--pri)}button.bad{background:transparent;border:1px solid var(--bad);color:var(--bad)}
button:disabled{opacity:.55}.full{width:100%;margin-top:10px}
.row{display:flex;gap:8px;align-items:center}.row>*{flex:1}.row>button{flex:0 0 auto}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.stat{background:var(--bg);border:1px solid var(--line);border-radius:16px;padding:12px}.stat b{display:block;font-size:24px;line-height:1.2}.stat span{font-size:12.5px;color:var(--mut)}
.chips{display:flex;gap:8px;overflow-x:auto;padding:2px 0 6px}.chip{flex:0 0 auto;border:1px solid var(--line);background:var(--card);color:var(--ink);padding:7px 13px;border-radius:999px;font-weight:600;font-size:14px}.chip.on{background:var(--pri);border-color:var(--pri);color:#fff}
.item{display:flex;justify-content:space-between;gap:10px;align-items:center;padding:11px 2px;border-top:1px solid var(--line)}.item:first-child{border-top:0}
.item .t{min-width:0;flex:1}.item .t b{display:block;word-break:break-all;font-weight:650}.item .t small{color:var(--mut)}
.tag{font-size:12px;padding:2px 9px;border-radius:999px;background:var(--pri2);color:var(--pri);font-weight:700;white-space:nowrap}.tag.ok{background:rgba(23,128,74,.14);color:var(--ok)}.tag.bad{background:rgba(192,57,43,.14);color:var(--bad)}.tag.warn{background:rgba(178,106,0,.16);color:var(--warn)}
.msg{min-height:20px;font-size:14px;margin-top:8px}.msg.ok{color:var(--ok)}.msg.bad{color:var(--bad)}.mut{color:var(--mut);font-size:13px}
nav{position:fixed;bottom:0;inset-inline:0;background:var(--card);border-top:1px solid var(--line);display:flex;padding-bottom:env(safe-area-inset-bottom);z-index:6}
nav button{flex:1;background:transparent;color:var(--mut);border-radius:0;padding:10px 2px;font-size:12.5px;font-weight:700}nav button.on{color:var(--pri);box-shadow:inset 0 3px 0 var(--pri)}
nav button i{display:block;font-style:normal;font-size:19px;line-height:1.2}
.sheet{position:fixed;inset:0;background:rgba(8,12,20,.5);z-index:20;display:flex;align-items:flex-end;justify-content:center}
.sheet>div{background:var(--bg);width:100%;max-width:640px;max-height:92vh;overflow:auto;border-radius:24px 24px 0 0;padding:16px;padding-bottom:max(18px,env(safe-area-inset-bottom));display:grid;gap:12px}
.kv{display:flex;justify-content:space-between;gap:10px;padding:6px 0;border-top:1px solid var(--line);font-size:14px}.kv:first-child{border-top:0}.kv span:first-child{color:var(--mut)}.kv span:last-child{word-break:break-all;text-align:end}
.login{max-width:420px;margin:12vh auto 0;padding:0 16px;display:grid;gap:12px}.login h1{text-align:center;font-size:22px}
</style></head><body>
<div id="app"></div>
<script>
"use strict";
const $app=document.getElementById("app");
let token="";try{token=sessionStorage.getItem("abAdmin")||""}catch(e){}
const LBL={start_balance:"رصيد البداية",daily_bonus:"هدية أول فتحة كل يوم",ad_reward:"نقاط الفيديو الواحد",max_ads_per_day:"الحد اليومي للفيديوهات",max_balance:"أقصى رصيد بالكسب",cost_per_page:"تكلفة الورقة (دفعة/كتاب)",cost_translate:"تكلفة حفظ الترجمة",max_scan_cost:"أقصى تكلفة حفظة واحدة"};
const PLAN={monthly:"شهري",yearly:"سنوي",lifetime:"مدى الحياة",days:"أيام محددة"};
const REASON={spend:"استهلاك",ad:"فيديو",admin:"من الإدارة"};
function h(tag,props,...kids){const e=document.createElement(tag);for(const k in (props||{})){const v=props[k];if(k==="class")e.className=v;else if(k.startsWith("on"))e.addEventListener(k.slice(2),v);else if(v!==null&&v!==undefined&&v!==false)e.setAttribute(k,v===true?"":v)}for(const c of kids.flat()){if(c===null||c===undefined||c===false)continue;e.append(c.nodeType?c:document.createTextNode(String(c)))}return e}
const num=n=>Number(n).toLocaleString("ar-EG");
const fdate=ms=>ms==null?"مدى الحياة":new Date(ms).toLocaleDateString("ar-EG",{year:"numeric",month:"short",day:"numeric"});
const fiso=s=>new Date(s).toLocaleDateString("ar-EG",{year:"numeric",month:"short",day:"numeric"});
async function api(method,path,body){
  const r=await fetch(path,{method,headers:{"X-Admin-Token":token,"Content-Type":"application/json"},body:body?JSON.stringify(body):undefined});
  let d={};try{d=await r.json()}catch(e){}
  if(r.status===401&&path!=="/v1/admin/health"){logout(true)}
  if(!r.ok){const det=d.detail;throw new Error(typeof det==="string"?det:(Array.isArray(det)?"بيانات غير صحيحة":("خطأ "+r.status)))}
  return d;
}
function msg(el,text,ok){el.textContent=text;el.className="msg "+(ok?"ok":"bad")}
function field(label,input){return h("div",null,h("label",null,label),input)}
function logout(expired){try{sessionStorage.removeItem("abAdmin")}catch(e){}token="";renderLogin(expired?"الرمز غلط أو انتهى، ادخل تاني":"")}

/* ---------- login ---------- */
function renderLogin(note){
  const t=h("input",{type:"password",autocomplete:"off",placeholder:"رمز الإدارة"});const m=h("div",{class:"msg bad"},note||"");
  const go=async()=>{token=t.value.trim();if(!token)return;try{await api("GET","/v1/admin/health");try{sessionStorage.setItem("abAdmin",token)}catch(e){}renderShell("dash")}catch(e){token="";msg(m,e.message==="admin is not configured"?"الإدارة لسه مش متظبطة على السيرفر":"الرمز غلط",false)}};
  t.addEventListener("keydown",e=>{if(e.key==="Enter")go()});
  $app.replaceChildren(h("div",{class:"login"},h("div",{class:"logo",style:"margin:0 auto;width:56px;height:56px;border-radius:18px;background:var(--pri);color:#fff;display:grid;place-items:center;font-weight:800"},"AB"),h("h1",null,"AB File Pro — الإدارة"),t,h("button",{onclick:go},"دخول"),m));
}

/* ---------- shell ---------- */
const TABS=[["dash","📊","الأرقام"],["acc","👥","الحسابات"],["pro","⭐","التفعيل"],["rules","⚙️","القواعد"],["health","🩺","الصحة"]];
let tab="dash";
function renderShell(which){
  tab=which||tab;
  const body=h("main",null);
  $app.replaceChildren(
    h("header",null,h("div",{class:"logo"},"AB"),h("h1",null,"AB File Pro — الإدارة"),h("button",{class:"alt",onclick:()=>logout(false)},"خروج")),
    body,
    h("nav",null,TABS.map(([k,ic,t])=>h("button",{class:k===tab?"on":"",onclick:()=>renderShell(k)},h("i",null,ic),t)))
  );
  ({dash:viewDash,acc:viewAccounts,pro:viewGrants,rules:viewRules,health:viewHealth})[tab](body);
}

/* ---------- dashboard ---------- */
async function viewDash(box){
  const m=h("div",{class:"msg"},"بحمّل…");box.append(m);
  try{const d=await api("GET","/v1/admin/dashboard");m.remove();
    const S=(v,l)=>h("div",{class:"stat"},h("b",null,num(v)),h("span",null,l));
    box.append(
      h("section",{class:"card"},h("h2",null,"الحسابات"),h("div",{class:"grid2"},S(d.accounts,"إجمالي الحسابات"),S(d.proAccounts,"مشتركو Pro"),S(d.newToday,"جديد النهاردة"),S(d.new7,"جديد آخر ٧ أيام"),S(d.suspended,"موقوفة"),S(d.grantsActive,"تفعيلات شغالة"))),
      h("section",{class:"card"},h("h2",null,"قرب الانتهاء"),h("div",{class:"stat"},h("b",null,num(d.expiring7)),h("span",null,"تفعيلات بتنتهي خلال ٧ أيام"))),
      h("section",{class:"card"},h("h2",null,"النقاط والفيديو"),h("div",{class:"grid2"},S(d.pointsInCirculation,"نقاط في أرصدة الناس"),S(d.pointsSpentAllTime,"نقاط اتصرفت"),S(d.videosAllTime,"فيديوهات اتشافت"),S(d.videos7,"فيديوهات آخر ٧ أيام"))));
  }catch(e){msg(m,e.message,false)}
}

/* ---------- accounts ---------- */
let accState={q:"",filter:"all"};
function viewAccounts(box){
  const q=h("input",{type:"search",placeholder:"ابحث بالإيميل",value:accState.q});
  const chips=h("div",{class:"chips"});const list=h("div");const info=h("div",{class:"msg"});const more=h("button",{class:"alt full"},"المزيد");
  let offset=0;
  const F=[["all","الكل"],["pro","Pro"],["free","مجاني"],["suspended","موقوف"]];
  const paint=()=>{chips.replaceChildren(...F.map(([k,t])=>h("button",{class:"chip"+(accState.filter===k?" on":""),onclick:()=>{accState.filter=k;paint();load(true)}},t)))};
  async function load(reset){
    if(reset){offset=0;list.replaceChildren()}
    try{const d=await api("GET","/v1/admin/accounts?q="+encodeURIComponent(accState.q)+"&filter="+accState.filter+"&offset="+offset);
      msg(info,"النتيجة: "+num(d.total)+" حساب",true);
      d.accounts.forEach(a=>list.append(h("div",{class:"item",onclick:()=>openAccount(a.email)},h("div",{class:"t"},h("b",null,a.email),h("small",null,(a.name||"—")+" · "+fiso(a.createdAt))),h("div",{style:"text-align:end;display:grid;gap:4px;justify-items:end"},a.suspended?h("span",{class:"tag bad"},"موقوف"):a.pro?h("span",{class:"tag ok"},"Pro"):h("span",{class:"tag"},"مجاني"),h("small",{class:"mut"},num(a.balance)+" نقطة")))));
      offset+=d.accounts.length;more.style.display=offset<d.total?"block":"none";
    }catch(e){msg(info,e.message,false)}
  }
  more.onclick=()=>load(false);
  let timer;q.addEventListener("input",()=>{accState.q=q.value;clearTimeout(timer);timer=setTimeout(()=>load(true),350)});
  box.append(h("section",{class:"card"},q,chips,info,list,more));paint();load(true);
}
function sheet(content){
  const close=()=>s.remove();
  const s=h("div",{class:"sheet",onclick:e=>{if(e.target===s)close()}},h("div",null,content(close)));
  document.body.append(s);return close;
}
async function openAccount(email){
  let d;try{d=await api("GET","/v1/admin/account?email="+encodeURIComponent(email))}catch(e){alert(e.message);return}
  sheet(close=>{
    const m=h("div",{class:"msg"});
    const refresh=()=>{close();openAccount(email);if(tab==="acc")renderShell("acc")};
    const run=async(fn,okText)=>{try{await fn();msg(m,okText,true);setTimeout(refresh,500)}catch(e){msg(m,e.message,false)}};
    const delta=h("input",{type:"number",value:"10",min:"-100",max:"100"});
    const plan=h("select",null,Object.entries(PLAN).map(([k,v])=>h("option",{value:k},v)));
    const days=h("input",{type:"number",value:"7",min:"1",max:"3650"});const note=h("input",{maxlength:"200",placeholder:"ملاحظة (اختياري)"});
    return [
      h("div",{class:"row"},h("h2",{style:"margin:0"},"تفاصيل الحساب"),h("button",{class:"alt",onclick:close},"إغلاق")),
      h("section",{class:"card"},
        [["الإيميل",d.email],["الاسم",d.name||"—"],["تاريخ التسجيل",fiso(d.createdAt)],["الرصيد",num(d.points.balance)+" نقطة"],["فيديوهات متبقية النهاردة",num(d.points.adsLeftToday)],["Pro",d.pro.active?(PLAN[d.pro.plan]||"")+" — لحد "+fdate(d.pro.endsAt):"غير مفعّل"],["أجهزة مسجّلة",num(d.sessions)],["الحالة",d.suspended?"موقوف":"شغال"]].map(([a,b])=>h("div",{class:"kv"},h("span",null,a),h("span",null,b)))),
      h("section",{class:"card"},h("h2",null,"تفعيل Pro"),field("الباقة",plan),field("عدد الأيام (للأيام المحددة)",days),field("ملاحظة",note),
        h("button",{class:"full",onclick:()=>run(()=>api("POST","/v1/admin/grants",{email:d.email,plan:plan.value,days:plan.value==="days"?Number(days.value):null,note:note.value}),"اتفعّل")},"فعّل"),
        h("button",{class:"bad full",onclick:()=>{if(confirm("إلغاء تفعيل Pro لـ "+d.email+"؟"))run(()=>api("DELETE","/v1/admin/grants/"+encodeURIComponent(d.email)),"اتلغى")}},"إلغاء التفعيل")),
      h("section",{class:"card"},h("h2",null,"النقاط"),field("زيادة أو نقص",delta),h("button",{class:"full",onclick:()=>run(()=>api("POST","/v1/admin/points",{email:d.email,delta:Number(delta.value)}),"اتعدّل الرصيد")},"طبّق")),
      h("section",{class:"card"},h("h2",null,"آخر حركات النقاط"),d.ledger.length?d.ledger.map(l=>h("div",{class:"kv"},h("span",null,(REASON[l.reason]||l.reason)+" · "+fiso(l.at)),h("span",{style:"direction:ltr"},(l.delta>0?"+":"")+l.delta))):h("div",{class:"mut"},"مفيش حركات")),
      h("section",{class:"card"},h("h2",null,"إجراءات"),
        h("button",{class:"alt full",onclick:()=>run(()=>api("POST","/v1/admin/accounts/suspend",{email:d.email,suspended:!d.suspended}),d.suspended?"اتفعّل الحساب":"اتوقف الحساب")},d.suspended?"إعادة تشغيل الحساب":"إيقاف الحساب"),
        h("button",{class:"bad full",onclick:async()=>{if(!confirm("حذف حساب "+d.email+" نهائيًا؟ مفيش رجوع."))return;try{await api("DELETE","/v1/admin/account?email="+encodeURIComponent(d.email));close();if(tab==="acc")renderShell("acc")}catch(e){msg(m,e.message,false)}}},"حذف الحساب نهائيًا")),
      m];
  });
}

/* ---------- grants ---------- */
let grantFilter="all",grantQ="";
function viewGrants(box){
  const email=h("input",{type:"email",inputmode:"email",autocomplete:"off",placeholder:"name@gmail.com"});
  const plan=h("select",null,Object.entries(PLAN).map(([k,v])=>h("option",{value:k},v)));
  const days=h("input",{type:"number",value:"7",min:"1",max:"3650"});const note=h("input",{maxlength:"200"});const m=h("div",{class:"msg"});
  const list=h("div");const info=h("div",{class:"msg"});const chips=h("div",{class:"chips"});const q=h("input",{type:"search",placeholder:"ابحث بالإيميل",value:grantQ});
  const F=[["all","الكل"],["active","شغال"],["expiring","قرب ينتهي"],["ended","منتهي"]];
  const paint=()=>chips.replaceChildren(...F.map(([k,t])=>h("button",{class:"chip"+(grantFilter===k?" on":""),onclick:()=>{grantFilter=k;paint();load()}},t)));
  async function load(){
    try{const d=await api("GET","/v1/admin/grants?status="+grantFilter+"&q="+encodeURIComponent(grantQ));list.replaceChildren();msg(info,num(d.grants.length)+" تفعيل",true);
      d.grants.forEach(g=>list.append(h("div",{class:"item"},h("div",{class:"t"},h("b",null,g.email),h("small",null,(PLAN[g.plan]||g.plan)+" · لحد "+fdate(g.endsAt)+(g.note?" · "+g.note:""))),h("div",{style:"display:grid;gap:6px;justify-items:end"},h("span",{class:"tag "+(g.active?(g.expiringSoon?"warn":"ok"):"bad")},g.active?(g.expiringSoon?"قرب ينتهي":"شغال"):"منتهي"),g.active?h("button",{class:"bad",style:"padding:5px 10px;font-size:13px",onclick:async()=>{if(!confirm("إلغاء تفعيل "+g.email+"؟"))return;try{await api("DELETE","/v1/admin/grants/"+encodeURIComponent(g.email));load()}catch(e){msg(info,e.message,false)}}},"إلغاء"):null))));
    }catch(e){msg(info,e.message,false)}
  }
  let timer;q.addEventListener("input",()=>{grantQ=q.value;clearTimeout(timer);timer=setTimeout(load,350)});
  box.append(
    h("section",{class:"card"},h("h2",null,"تفعيل Pro بالإيميل"),field("الإيميل",email),field("الباقة",plan),field("عدد الأيام (للأيام المحددة)",days),field("ملاحظة",note),
      h("button",{class:"full",onclick:async()=>{try{const p=plan.value;const d=await api("POST","/v1/admin/grants",{email:email.value,plan:p,days:p==="days"?Number(days.value):null,note:note.value});msg(m,"اتفعّل: "+d.email+" — لحد "+fdate(d.endsAt),true);load()}catch(e){msg(m,e.message,false)}}},"فعّل"),m,
      h("div",{class:"mut"},"التفعيل بيشتغل حتى لو الشخص لسه ما سجّلش دخول. لو فعّلت تاني لنفس الإيميل بيتمدد.")),
    h("section",{class:"card"},h("h2",null,"التفعيلات"),q,chips,info,list));
  paint();load();
}

/* ---------- rules ---------- */
async function viewRules(box){
  const m=h("div",{class:"msg"},"بحمّل…");box.append(m);
  try{const d=await api("GET","/v1/admin/rules");m.remove();
    const inputs={};const fields=Object.keys(LBL).filter(k=>k in d.rules).map(k=>{const l=d.limits[k];const i=h("input",{type:"number",value:d.rules[k],min:l.min,max:l.max,inputmode:"numeric"});inputs[k]=i;return field(LBL[k]+" ("+l.min+" – "+l.max+")",i)});
    const ms=h("div",{class:"msg"});const gift=h("input",{type:"number",value:"5",min:"1",max:"50"});const mg=h("div",{class:"msg"});
    box.append(
      h("section",{class:"card"},h("h2",null,"قواعد النقاط"),fields,
        h("button",{class:"full",onclick:async()=>{const rules={};for(const k in inputs)rules[k]=Number(inputs[k].value);try{await api("PUT","/v1/admin/rules",{rules});msg(ms,"اتحفظت القواعد",true)}catch(e){msg(ms,e.message,false)}}},"احفظ القواعد"),ms,
        h("div",{class:"mut"},"التغيير بيطبّق على الحسابات فورًا. تكلفة الورقة والترجمة بتتطبّق في التطبيق لما نربطه بالقواعد في النسخة الجاية.")),
      h("section",{class:"card"},h("h2",null,"هدية نقاط لكل الحسابات"),field("عدد النقاط",gift),
        h("button",{class:"alt full",onclick:async()=>{if(!confirm("تدّي "+gift.value+" نقطة لكل الحسابات؟"))return;try{const r=await api("POST","/v1/admin/points/gift-all",{delta:Number(gift.value)});msg(mg,"اتحدّث "+num(r.accountsUpdated)+" حساب",true)}catch(e){msg(mg,e.message,false)}}},"ابعت الهدية"),mg,
        h("div",{class:"mut"},"الهدية ما بتعدّيش أقصى رصيد.")));
  }catch(e){msg(m,e.message,false)}
}

/* ---------- health ---------- */
async function viewHealth(box){
  const m=h("div",{class:"msg"},"بحمّل…");box.append(m);
  try{const d=await api("GET","/v1/admin/health");m.remove();
    const row=(l,ok,txt)=>h("div",{class:"kv"},h("span",null,l),h("span",{class:"tag "+(ok?"ok":"bad")},txt));
    box.append(h("section",{class:"card"},h("h2",null,"حالة السيرفر"),row("قاعدة البيانات",d.database,d.database?"شغالة":"مشكلة"),row("الدخول بجوجل",d.googleConfigured,d.googleConfigured?"متظبط ("+d.googleClientIds+")":"لسه مش متظبط"),row("رمز الإدارة",d.adminConfigured,d.adminConfigured?"متظبط":"ضعيف أو ناقص"),h("div",{class:"kv"},h("span",null,"وقت السيرفر"),h("span",null,new Date(d.serverTime).toLocaleString("ar-EG")))),
      d.googleConfigured?null:h("section",{class:"card"},h("div",{class:"mut"},"الدخول بجوجل محتاج متغير GOOGLE_CLIENT_IDS على السيرفر (الـ Web client ID من Google Cloud).")));
  }catch(e){msg(m,e.message,false)}
}

token?api("GET","/v1/admin/health").then(()=>renderShell("dash")).catch(()=>renderLogin("")):renderLogin("");
</script></body></html>"""
