/* GLASS theme: light/dark wallpapers, automatic night schedule, change notifications. */
(function(){
'use strict';
var G=window.Glass, $=G.$, T=G.cfg.theme, listeners=[];
function night(){ var h=new Date().getHours(); return h>=19||h<7; }
var dark=night(), lastNight=dark;
var wpL=$('wp'), wpD=$('wpd');
wpL.style.backgroundImage='url('+(T.wallpaperLight||'wallpaper.jpg')+')';
(function(){ var p=new Image();
  p.onload=function(){ wpD.style.backgroundImage='url('+T.wallpaperDark+')'; };
  p.onerror=function(){ wpD.style.backgroundImage='url('+(T.wallpaperLight||'wallpaper.jpg')+')'; wpD.classList.add('fallback'); };
  p.src=T.wallpaperDark||'wallpaper2.jpg'; })();
function apply(){ document.body.classList.toggle('dark',dark); G.state.dark=dark; listeners.forEach(function(f){ try{f(dark);}catch(e){} }); }
G.theme={
  isDark:function(){ return dark; },
  set:function(v){ dark=!!v; apply(); },
  toggle:function(){ G.sound.click(); dark=!dark; apply(); },
  onChange:function(f){ listeners.push(f); },
  /* auto dark 19:00-07:00. A manual toggle holds until the next automatic switch. */
  tick:function(){ var n=night(); if(n!==lastNight){ lastNight=n; dark=n; apply(); } },
  init:apply
};
})();
