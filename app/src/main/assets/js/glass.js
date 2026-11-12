/* GLASS core: namespace, config merge, Android bridge wrapper, storage, toast, sounds. */
(function(){
'use strict';
var G=window.Glass={};
G.$=function(id){return document.getElementById(id);};
G.state={zone:'rows',dark:false};
G.zones={};                                  // zone name -> {key(k,e)}; filled in by modules

/* ---------- config (apps.js is the user-editable source) ---------- */
G.cfg=(function(){
  var lay={columns:5,showLabels:false,rowGap:2.4,clockGap:5.4,clockScale:.6,collapsedClockTop:-1.6,collapsedGap:1.6,bottomMargin:3.2};
  var u=window.LAYOUT||{}; for(var k in u) lay[k]=u[k]; lay.columns=5;      // 5 per row, always
  return {
    layout:lay,
    apps:window.APPS||[],
    theme:window.THEME||{},
    ui:window.UI_ICONS||{},
    overrides:window.ICON_OVERRIDES||{},     // package id -> image path (visual only)
    aliases:window.SEARCH_ALIASES||{},       // package id -> extra search words
    entertainment:window.ENTERTAINMENT||{title:'Entertainment',apps:[]}
  };
})();

/* ---------- Android bridge (safe in a normal browser too) ---------- */
G.bridge={
  call:function(fn,a){
    try{
      if(typeof AndroidBridge==='undefined'||typeof AndroidBridge[fn]==='undefined') return null;
      return (a===undefined)?AndroidBridge[fn]():AndroidBridge[fn](a);
    }catch(e){ return null; }
  },
  json:function(fn,a){
    var r=G.bridge.call(fn,a);
    if(typeof r==='string'){ try{ return JSON.parse(r); }catch(e){ return null; } }
    return r;
  },
  available:function(fn){ try{ return typeof AndroidBridge!=='undefined'&&typeof AndroidBridge[fn]!=='undefined'; }catch(e){ return false; } }
};

/* ---------- small persistent store (localStorage, namespaced) ---------- */
G.store={
  get:function(k,def){ try{ var v=localStorage.getItem('glass.'+k); return v===null?def:JSON.parse(v); }catch(e){ return def; } },
  set:function(k,v){ try{ localStorage.setItem('glass.'+k,JSON.stringify(v)); return true; }catch(e){ return false; } },
  remove:function(k){ try{ localStorage.removeItem('glass.'+k); }catch(e){} }
};

/* ---------- toast ---------- */
var tt;
G.toast=function(m){ var t=G.$('toast'); t.textContent=m; t.classList.add('on'); clearTimeout(tt); tt=setTimeout(function(){t.classList.remove('on');},3200); };

/* ---------- sounds ---------- */
var SOUND=window.SOUND||{enabled:true,volume:.6};
function mkSound(src){
  var pool=[],n=0,i;
  for(i=0;i<3;i++){ var a=new Audio(src); a.preload='auto'; try{a.load();}catch(e){} pool.push(a); }
  return function(){
    if(SOUND.enabled===false) return;
    var a=pool[n++%pool.length];
    try{ a.volume=(SOUND.volume==null?.6:SOUND.volume); a.currentTime=0; var pr=a.play(); if(pr&&pr.catch) pr.catch(function(){}); }catch(e){}
  };
}
G.sound={nav:mkSound('sounds/navigate.wav'),click:mkSound('sounds/click.wav')};

/* ---------- keyboard typing sounds: a few real variants per key type, so repeats don't sound robotic ---------- */
function mkKeyPool(files){
  var pool=[],n=0,i;
  for(i=0;i<4;i++){ var a=new Audio(); a.preload='auto'; pool.push(a); }
  return function(){
    if(SOUND.enabled===false) return;
    var src=files[Math.floor(Math.random()*files.length)];
    var a=pool[n++%pool.length];
    try{ a.volume=(SOUND.keyVolume==null?.5:SOUND.keyVolume); a.src=src; a.currentTime=0; var pr=a.play(); if(pr&&pr.catch) pr.catch(function(){}); }catch(e){}
  };
}
var KEY_SOUNDS={
  alpha:mkKeyPool(['sounds/keys/alpha/d1.mp3','sounds/keys/alpha/d2.mp3','sounds/keys/alpha/d3.mp3','sounds/keys/alpha/d4.mp3']),
  space:mkKeyPool(['sounds/keys/space/d1.mp3','sounds/keys/space/d2.mp3','sounds/keys/space/d3.mp3','sounds/keys/space/d4.mp3','sounds/keys/space/d5.mp3']),
  enter:mkKeyPool(['sounds/keys/enter/d1.mp3','sounds/keys/enter/d2.mp3','sounds/keys/enter/d3.mp3','sounds/keys/enter/d4.mp3']),
  alt:mkKeyPool(['sounds/keys/alt/d1.mp3','sounds/keys/alt/d2.mp3','sounds/keys/alt/d3.mp3','sounds/keys/alt/d4.mp3','sounds/keys/alt/d5.mp3'])
};
/* type: 'alpha' (letters/numbers/symbols), 'space', 'enter' (search/submit), 'alt' (shift/backspace/clear/mode) */
G.sound.key=function(type){ (KEY_SOUNDS[type]||KEY_SOUNDS.alpha)(); };


/* ---------- spatial D-pad navigation: nearest element in a direction (used by the Control Centre grid) ---------- */
G.spatial={
  /* els: array of elements, i: current index, dir: 37|38|39|40. Returns the new index, or -1 if nothing lies that way. */
  move:function(els,i,dir){
    var c=els[i].getBoundingClientRect(), cx=c.left+c.width/2, cy=c.top+c.height/2, best=-1, bs=1e9, j, r, dx, dy, prim, perp, s;
    for(j=0;j<els.length;j++){
      if(j===i) continue; r=els[j].getBoundingClientRect(); dx=r.left+r.width/2-cx; dy=r.top+r.height/2-cy;
      if(dir===39){ if(dx<=4) continue; prim=dx; perp=Math.abs(dy); }
      else if(dir===37){ if(dx>=-4) continue; prim=-dx; perp=Math.abs(dy); }
      else if(dir===40){ if(dy<=4) continue; prim=dy; perp=Math.abs(dx); }
      else { if(dy>=-4) continue; prim=-dy; perp=Math.abs(dx); }
      s=prim+perp*2.5; if(s<bs){ bs=s; best=j; }
    }
    return best;
  }
};

/* ---------- performance guard ----------
   Overlays (Control Centre, Search) use one real backdrop blur each. If the box cannot animate them smoothly, Glass falls back to the
   denser no-blur glass automatically (after two slow openings) and remembers it. apps.js: PERF.blur = true (always blur) | false (never) | 'auto' (default).
   To undo an automatic fallback: Glass.store.remove('noblur') and restart. */
G.perf={
  init:function(){
    var m=(window.PERF&&window.PERF.blur!==undefined)?window.PERF.blur:'auto';
    if(m===false||(m==='auto'&&G.store.get('noblur',false))) document.body.classList.add('noblur');
  },
  watch:function(ms){
    var m=(window.PERF&&window.PERF.blur!==undefined)?window.PERF.blur:'auto';
    if(m!=='auto'||document.body.classList.contains('noblur')||!window.requestAnimationFrame) return;
    var t0=performance.now(), last=t0, n=0, slow=0;
    (function f(t){
      var d=t-last; last=t; if(d>0&&d<400){ n++; if(d>42) slow++; }
      if(t-t0<ms){ requestAnimationFrame(f); return; }
      if(n>8&&slow/n>0.5){
        var k=G.store.get('slowopen',0)+1; G.store.set('slowopen',k);
        if(k>=2){ G.store.set('noblur',true); document.body.classList.add('noblur'); }
      } else if(n>8) G.store.set('slowopen',0);
    })(t0);
  }
};

/* ---------- helpers ---------- */
G.initials=function(n){ if(!n) return '?'; var w=n.trim().split(/\s+/); return (w[0][0]+(w[1]?w[1][0]:'')).toUpperCase(); };
G.exitApp=function(){ if(G.bridge.call('goHome')===null) G.toast('Use Home to leave Glass Launcher'); };
G.launch=function(a){
  G.sound.click();
  if(!a.id){ G.toast(a.name+' is not installed on this box.'); return false; }
  var r=G.bridge.call('launchPackage',a.id);
  if(r===null) G.toast(a.name+' could not be opened.');
  else if(r!==true) G.toast(a.name+' is not installed on this box.');
  else if(G.recent) G.recent.record(a);          // Recently Used: only real, successful launches
  return r===true;
};
/* real mouse movement only: browsers fire mouseenter/mousemove again when content slides under a parked pointer, which would steal focus */
G.pointer={x:-1e4,y:-1e4,moved:function(e){        // anchor only advances on a genuine move (>=4px), so rounding jitter or a synthetic move can never count
  if(Math.abs(e.clientX-G.pointer.x)+Math.abs(e.clientY-G.pointer.y)<4) return false;
  G.pointer.x=e.clientX; G.pointer.y=e.clientY; return true; }};
G.openSettings=function(kind){
  if(G.bridge.call('openSettings',kind)!==true) G.toast('That settings page is not available on this box.');
};
G.setZone=function(z){ G.state.zone=z; };
})();
