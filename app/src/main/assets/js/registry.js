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

/* Glass 2.0: the same ranking computed by the Rust core (libglass, AndroidBridge.core*). The index is normalised once per
   Search session instead of re-normalising every entry on every keystroke. Any problem (no native library, PERF.core:'js',
   an unexpected entry kind, a bridge error) drops back to the JavaScript path below, which is unchanged. */
var core={on:null,ents:null,sent:null}, STRIP=/[\u001e\u001f]/g;
function clean(s){ return String(s||'').replace(STRIP,''); }   // separators are removed by norm() anyway, so ranking is unaffected
function wire(list){
  return list.map(function(e){ return [e.kind==='app'?'app':'shortcut',clean(e.title)].concat((e.keywords||[]).map(clean)).join('\u001f'); }).join('\u001e');
}
function coreOn(){
  if(core.on===null) core.on=!(window.PERF&&window.PERF.core==='js')&&G.bridge.available('coreSearch')&&G.bridge.available('coreSearchIndex');
  return core.on;
}
function coreQuery(text,max){
  if(!coreOn()||!(max===undefined||max>0)) return null;
  try{
    if(core.ents===null){
      var ents=G.registry.entries(), i;
      /* registry.js orders unknown kinds with an asymmetric comparator; only app/shortcut are ported exactly */
      for(i=0;i<ents.length;i++) if(ents[i].kind!=='app'&&ents[i].kind!=='shortcut'){ core.ents=false; return null; }
      var w=wire(ents);
      if(w!==core.sent){ if(AndroidBridge.coreSearchIndex(w)!==ents.length){ core.on=false; return null; } core.sent=w; }
      core.ents=ents;
    }
    if(!core.ents) return null;
    var r=AndroidBridge.coreSearch(String(text),max||24);
    if(typeof r!=='string'){ core.on=false; return null; }
    if(!r) return [];
    return r.split(',').map(function(i){ return core.ents[+i]; });
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
    var q=norm(text), qc=compact(q); if(!q) return [];
    var res=[];
    G.registry.entries().forEach(function(e){ var s=score(e,q,qc); if(s>0) res.push({e:e,s:s}); });
    res.sort(function(a,b){
      if(b.s!==a.s) return b.s-a.s;
      if(a.e.kind!==b.e.kind) return a.e.kind==='app'?-1:1;
      return norm(a.e.title)<norm(b.e.title)?-1:1;
    });
    return res.slice(0,max||24).map(function(r){return r.e;});
  }
};

/* provider 1: installed applications (live from Android) */
G.registry.register({entries:function(){
  return G.apps.installed.filter(function(x){ return x.show!==false&&x.id!=='com.glasslauncher'&&!G.apps.HIDDEN.test(x.id); }).map(function(x){
    return {id:'app:'+x.id,kind:'app',title:x.name,subtitle:x.id,pkg:x.id,tag:'App',
      keywords:G.cfg.aliases[x.id]||[],
      run:function(){ G.launch({name:x.name,id:x.id}); }};
  });
}});
})();
