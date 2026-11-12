/* GLASS built-in searchable shortcuts. Add rows here (or call Glass.registry.addShortcut elsewhere); nothing else needs to change. */
(function(){
'use strict';
var G=window.Glass, R=G.registry;
/* Android system settings pages. Entries vanish automatically if this box has no such screen. */
[
  {kind:'wifi',     title:'Wi-Fi',            sub:'Network settings',               icon:'wifi',      kw:['wifi','wireless','network','internet','connection']},
  {kind:'bluetooth',title:'Bluetooth',        sub:'Bluetooth settings',             icon:'bluetooth', kw:['pairing','remote','headphones','devices']},
  {kind:'display',  title:'Display',          sub:'Display settings',               icon:'display',   kw:['screen','resolution','hdmi','picture']},
  {kind:'sound',    title:'Sound',            sub:'Sound settings',                 icon:'volume',    kw:['audio','speaker','surround']},
  {kind:'apps',     title:'Apps',             sub:'Manage installed applications',  icon:'apps',      kw:['application management','uninstall','manage apps','permissions']},
  {kind:'storage',  title:'Storage',          sub:'Storage settings',               icon:'storage',   kw:['space','memory','disk']},
  {kind:'datetime', title:'Date & time',      sub:'Date and time settings',         icon:'clock',     kw:['clock','timezone','time zone']},
  {kind:'keyboard', title:'Keyboard',         sub:'Keyboard and input settings',    icon:'keyboard',  kw:['input','typing','language']},
  {kind:'accessibility',title:'Accessibility',sub:'Accessibility settings',         icon:'access',    kw:['captions','talkback','high contrast']},
  {kind:'device',   title:'About this box',   sub:'Model, Android version, status', icon:'device',    kw:['device info','device information','about','version','model','system info']},
  {kind:'main',     title:'Settings',         sub:'Android TV Settings',            icon:'settings',  kw:['android settings','system','preferences','tv settings'],
     supersededBy:['com.android.tv.settings','com.android.settings']}
].forEach(function(s){
  R.addShortcut({id:'set:'+s.kind,title:s.title,subtitle:s.sub,icon:s.icon,tag:'Settings',keywords:s.kw,supersededBy:s.supersededBy,
    available:function(){ return s.kind==='main'||R.hasSettings(s.kind); },
    run:function(){ G.sound.click(); G.openSettings(s.kind); }});
});

/* Glass's own controls */
R.addShortcut({id:'glass:cc',title:'Control Centre',subtitle:'Wi-Fi, Bluetooth, volume and appearance',icon:'controls',tag:'Glass',keywords:['controls','quick settings','panel'],
  run:function(){ G.search.close(true); G.cc.open({from:'rows'}); }});
R.addShortcut({id:'glass:volume',title:'Volume',subtitle:'Open the Glass volume controls',icon:'volume',tag:'Glass',keywords:['sound level','mute','louder','quieter'],
  run:function(){ G.search.close(true); G.cc.open({from:'rows',focus:'vol'}); }});
R.addShortcut({id:'glass:theme',title:'Appearance',subtitle:'Switch between light and dark',icon:'themeDark',tag:'Glass',keywords:['dark mode','light mode','theme','night','day'],
  run:function(){ G.theme.toggle(); G.toast(G.theme.isDark()?'Dark appearance':'Light appearance'); }});

/* Extra search words for installed apps, keyed by package id (extend in apps.js: window.SEARCH_ALIASES) */
var A={'com.android.vending':['play store','google play','store','app store','download apps']};
for(var k in A) if(!G.cfg.aliases[k]) G.cfg.aliases[k]=A[k];
})();
