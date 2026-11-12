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
    utilities:window.UTILITIES||{title:'Utilities',slots:[]}
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

/* ---------- helpers ---------- */
G.initials=function(n){ if(!n) return '?'; var w=n.trim().split(/\s+/); return (w[0][0]+(w[1]?w[1][0]:'')).toUpperCase(); };
G.exitApp=function(){ if(G.bridge.call('goHome')===null) G.toast('Use Home to leave Glass Launcher'); };
G.launch=function(a){
  G.sound.click();
  if(!a.id){ G.toast('No app ID set for '+a.name); return false; }
  var r=G.bridge.call('launchPackage',a.id);
  if(r===null) G.toast(a.name+' could not be opened.');
  else if(r!==true) G.toast(a.name+' is not installed on this box.');
  return r===true;
};
G.openSettings=function(kind){
  if(G.bridge.call('openSettings',kind)!==true) G.toast('That settings page is not available on this box.');
};
G.setZone=function(z){ G.state.zone=z; };
})();
