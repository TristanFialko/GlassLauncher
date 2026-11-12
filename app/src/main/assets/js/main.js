/* GLASS main: boot, clock, key routing between zones (rows | hdr | cc | search), installed-app list overlay. */
(function(){
'use strict';
var G=window.Glass, $=G.$, st=G.state;

/* installed apps + IDs list (opened by an app entry with action:'list'; kept from Phase 2) */
var idsEl=$('ids');
G.ids={
  show:function(){
    idsEl.innerHTML=''; var h=document.createElement('h3'); h.textContent='Installed apps and their IDs (Up/Down to scroll, Back to close)'; idsEl.appendChild(h);
    var l=G.apps.installed.filter(function(x){return x.show!==false&&!/^(org\.tizen|com\.samsung)\./.test(x.id);}).sort(function(a,b){return a.name.toLowerCase()<b.name.toLowerCase()?-1:1;});
    if(!l.length){ var m=document.createElement('div'); m.textContent='No app list available.'; idsEl.appendChild(m); }
    l.forEach(function(x){ var d=document.createElement('div'),b=document.createElement('b'),s=document.createElement('span'); b.textContent=x.name; s.textContent=x.id; d.appendChild(b); d.appendChild(s); idsEl.appendChild(d); });
    idsEl.scrollTop=0; idsEl.classList.add('on');
  },
  isOpen:function(){ return idsEl.classList.contains('on'); }
};

/* One router for TV remote, keyboard and (via the synthetic Back from Java) mouse right-click.
   Escape and Backspace become "Back" everywhere except typing in Search, where Backspace deletes. Tab / Shift+Tab walk the focusable controls. */
document.addEventListener('keydown',function(e){
  var k=e.keyCode;
  if(k===27||(k===8&&st.zone!=='search')){ e.preventDefault(); k=10009; }
  if(G.ids.isOpen()){ if(k===40) idsEl.scrollTop+=120; else if(k===38) idsEl.scrollTop-=120; else if(k===10009||k===13) idsEl.classList.remove('on'); e.preventDefault(); return; }
  var z=G.zones[st.zone]; if(!z) return;
  if(k===9){ e.preventDefault(); if(z.tab) z.tab(e.shiftKey?-1:1); return; }
  if(e.repeat&&k===13&&st.zone!=='search') return;      // holding OK must not launch / toggle repeatedly (Search keeps repeat so Backspace-style keys can auto-repeat)
  z.key(k,e);
});
window.addEventListener('resize',function(){ G.home.layout(); G.home.render(true); });

function tick(){
  var d=new Date(), h=d.getHours()%12||12, m=('0'+d.getMinutes()).slice(-2);
  $('clock').textContent=h+':'+m;
  G.theme.tick();
  $('date').textContent=d.toLocaleDateString('en-GB',{weekday:'long',day:'numeric',month:'long'});
}
if(G.cfg.ui.invert===false) document.body.classList.add('noinvert');
if((window.PERF||{lite:true}).lite!==false) document.body.classList.add('lite');
G.perf.init(); G.theme.init(); tick(); G.apps.load(); G.recent.refresh(); G.home.rebuild(); setInterval(tick,10000);   // tick first: layout measures the clock
})();
