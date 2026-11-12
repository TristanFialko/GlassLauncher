/* GLASS search registry: providers contribute entries; one scorer ranks them. No per-query string special-casing.
   To add something searchable, call Glass.registry.register(provider) or Glass.registry.addShortcut(entry).
   entry = { id, kind:'app'|'shortcut', title, subtitle, keywords:[...], pkg?, icon?:svgKey, tag?, supersededBy?:[pkg], available?:fn, run:fn } */
(function(){
'use strict';
var G=window.Glass;
function norm(s){ return String(s||'').toLowerCase().replace(/[^a-z0-9\u00c0-\uffff ]+/g,'').replace(/\s+/g,' ').trim(); }
function compact(s){ return s.replace(/ /g,''); }
function subseq(q,t){ var i=0; for(var j=0;j<t.length&&i<q.length;j++) if(t.charAt(j)===q.charAt(i)) i++; return i===q.length; }
/* 0-100 for one candidate string against the query */
function fieldScore(text,q,qc,fuzzy){
  var t=norm(text); if(!t) return 0;
  var tc=compact(t);
  if(t===q||tc===qc) return 100;
  if(t.indexOf(q)===0||tc.indexOf(qc)===0) return 85;
  var w=t.split(' '); for(var i=0;i<w.length;i++) if(w[i].indexOf(q)===0) return 70;
  if(tc.indexOf(qc)>=0) return 50;
  if(fuzzy&&qc.length>=4&&subseq(qc,tc)) return 20;     // loose match: titles only, 4+ characters
  return 0;
}
function score(e,q,qc){
  var best=fieldScore(e.title,q,qc,true);
  (e.keywords||[]).forEach(function(k){ var s=fieldScore(k,q,qc)*0.92; if(s>best) best=s; });
  return best>0?best-Math.min(5,norm(e.title).length*0.02):0;
}
var providers=[], shortcuts=[], settingsOk={};

/* Glass 2.0: Search sessions are prepared and ranked by the Rust core (libglass, AndroidBridge.core*). Once per Search
   session JavaScript gathers live facts only - the installed list as Android reported it, alias lists, every other
   provider/shortcut entry with its available() answer - into one compact snapshot; Rust applies the entries() rules
   (visibility, hidden vendors, Glass itself, supersededBy), orders and normalises the entries and builds the index
   (rust/glass-core/src/snapshot.rs). Entry objects are created only for results actually shown. Any problem (no native
   library, PERF.core:'js', data outside what the Rust port covers, a bridge error) drops back to the JavaScript path
   below, which is unchanged. */
var core={on:null,ents:null,sent:null,refs:null,src:null,isApp:null,made:null}, STRIP=/[\u001c-\u001f]/g, CTL=/[\u001c-\u001f]/;
/* a lone UTF-16 surrogate has no UTF-8 form, so such (practically impossible) text stays on the JavaScript path */
var LONE=/[\ud800-\udbff](?![\udc00-\udfff])|(^|[^\ud800-\udbff])[\udc00-\udfff]/;
function clean(s){ s=String(s||''); return CTL.test(s)?s.replace(STRIP,''):s; }   // separators are removed by norm() anyway, so ranking is unaffected
function wire(list){
  return list.map(function(e){ return [e.kind==='app'?'app':'shortcut',clean(e.title)].concat((e.keywords||[]).map(clean)).join('\u001f'); }).join('\u001e');
}
var appProvider=null;
function appEntry(x){
  return {id:'app:'+x.id,kind:'app',title:x.name,subtitle:x.id,pkg:x.id,tag:'App',
    keywords:G.cfg.aliases[x.id]||[],
    run:function(){ G.launch({name:x.name,id:x.id}); }};
}
/* snapshot.rs wire format, built by string concatenation (cheapest in V8); false when outside what the Rust port covers */
function listed(e,acc){
  if(!e) return false;
  var av=!(e.available&&!e.available()), sup=e.supersededBy, kw=e.keywords||[], s='', k, n=0;
  if((sup&&!Array.isArray(sup))||!Array.isArray(kw)) return false;
  if(sup) for(k=0;k<sup.length;k++) if(typeof sup[k]==='string'&&!CTL.test(sup[k])) s+=(n++?'\u001d':'')+sup[k];
  s='\u001ee\u001f'+(e.kind==='app'?'app':e.kind==='shortcut'?'shortcut':'?')+'\u001f'+(av?'1':'0')+'\u001f'+s+'\u001f'+clean(e.title);
  for(k=0;k<kw.length;k++) s+='\u001f'+clean(kw[k]);
  acc.w+=s; acc.src.push(e); acc.isApp.push(false);
  return true;
}
function snapshot(){
  var acc={w:'glass-snapshot\u001f1',src:[],isApp:[]}, inst=G.apps.installed, aliases=G.cfg.aliases, i, j, k, list, x, al, s;
  for(i=0;i<providers.length;i++){
    if(providers[i]===appProvider){
      for(j=0;j<inst.length;j++){
        x=inst[j];
        if(!x||typeof x.id!=='string'||CTL.test(x.id)) return null;
        al=aliases[x.id]||[]; if(!Array.isArray(al)) return null;
        s='\u001ei\u001f'+x.id+'\u001f'+clean(x.name)+(x.show===false?'\u001f0':'\u001f1');
        for(k=0;k<al.length;k++) s+='\u001f'+clean(al[k]);
        acc.w+=s; acc.src.push(x); acc.isApp.push(true);
      }
      continue;
    }
    try{ list=providers[i].entries()||[]; }catch(e){ continue; }   // as in entries(): a failing provider contributes nothing
    if(!Array.isArray(list)) return null;
    for(j=0;j<list.length;j++) if(!listed(list[j],acc)) return null;
  }
  for(i=0;i<shortcuts.length;i++) if(!listed(shortcuts[i],acc)) return null;
  return {wire:acc.w,src:acc.src,isApp:acc.isApp};
}
function coreOn(){
  if(core.on===null) core.on=!(window.PERF&&window.PERF.core==='js')&&G.bridge.available('coreSearch')&&G.bridge.available('coreSearchSnapshot');
  return core.on;
}
/* prepares the Rust index for this Search session; false keeps the session on the JavaScript path */
function prepare(){
  var s=snapshot(), i;
  if(!s||LONE.test(s.wire)) return false;
  if(s.wire!==core.sent){
    var r=AndroidBridge.coreSearchSnapshot(s.wire);
    if(typeof r!=='string') return false;               // malformed or unsupported snapshot (e.g. an unknown entry kind)
    core.sent=s.wire; core.refs=r?r.split(',').map(Number):[];
  }
  for(i=0;i<core.refs.length;i++) if(!(core.refs[i]>=0&&core.refs[i]<s.src.length)) return false;
  core.src=s.src; core.isApp=s.isApp; core.made={}; core.ents=core.refs;
  return true;
}
function entryAt(n){
  var r=core.ents[n];
  if(!core.made.hasOwnProperty(r)) core.made[r]=core.isApp[r]?appEntry(core.src[r]):core.src[r];
  return core.made[r];
}
function coreQuery(text,max){
  if(!coreOn()||!(max===undefined||max>0)||LONE.test(String(text))) return null;
  try{
    if(core.ents===null&&!prepare()){ core.ents=false; return null; }
    if(!core.ents) return null;
    var r=AndroidBridge.coreSearch(String(text),max||24);
    if(typeof r!=='string'){ core.on=false; return null; }
    if(!r) return [];
    return r.split(',').map(function(i){ return entryAt(+i); });
  }catch(e){ core.on=false; return null; }
}

G.registry={
  norm:norm,
  wire:wire,
  /* entries are snapshotted per Search session for the Rust index; search.js calls this whenever Search opens */
  invalidate:function(){ core.ents=null; },
  register:function(p){ providers.push(p); core.ents=null; },
  addShortcut:function(e){ e.kind='shortcut'; shortcuts.push(e); core.ents=null; },
  /* settings availability is asked from Android once and cached */
  hasSettings:function(kind){
    if(!settingsOk.hasOwnProperty(kind)){ var r=G.bridge.call('hasSettings',kind); settingsOk[kind]=(r===null)?true:(r===true); }
    return settingsOk[kind];
  },
  entries:function(){
    var all=[]; providers.forEach(function(p){ try{ all=all.concat(p.entries()||[]); }catch(e){} });
    var out=[]; all.concat(shortcuts).forEach(function(e){
      if(e.available&&!e.available()) return;
      if(e.supersededBy&&e.supersededBy.some(function(p){return G.apps.isInstalled(p);})) return;
      out.push(e);
    });
    return out;
  },
  query:function(text,max){
    if(!norm(text)) return [];
    return coreQuery(text,max)||G.registry.queryJs(text,max);
  },
  /* the original JavaScript ranking: fallback for query() and the reference for parity checks */
  queryJs:function(text,max){
    var q=norm(text), qc=compact(q); if(!q) return [];
    var res=[];
    G.registry.entries().forEach(function(e){ var s=score(e,q,qc); if(s>0) res.push({e:e,s:s}); });
    res.sort(function(a,b){
      if(b.s!==a.s) return b.s-a.s;
      if(a.e.kind!==b.e.kind) return a.e.kind==='app'?-1:1;
      return norm(a.e.title)<norm(b.e.title)?-1:1;
    });
    return res.slice(0,max||24).map(function(r){return r.e;});
  },
  /* the snapshot text sent to Rust for this session, or null if it would stay on JavaScript (tests and benchmarks) */
  snapshotWire:function(){ var s=snapshot(); return s&&s.wire; },
  /* every entry of a freshly prepared Rust snapshot, in order (null when the session would use JavaScript); for parity checks */
  snapshotEntries:function(){
    core.ents=null;
    try{ if(!coreOn()||!prepare()){ core.ents=null; return null; } }catch(e){ core.ents=null; return null; }
    var out=[], i; for(i=0;i<core.ents.length;i++) out.push(entryAt(i));
    return out;
  },
  /* true while the current Search session is being ranked by the Rust core */
  coreActive:function(){ return core.on===true&&!!core.ents; }
};

/* provider 1: installed applications (live from Android). Its rules are ported to Rust (snapshot.rs); this function
   remains the JavaScript fallback and the parity reference. */
appProvider={entries:function(){
  return G.apps.installed.filter(function(x){ return x.show!==false&&x.id!=='com.glasslauncher'&&!G.apps.HIDDEN.test(x.id); }).map(appEntry);
}};
G.registry.register(appProvider);
})();
