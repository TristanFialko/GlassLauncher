/* GLASS icons: built-in SVG set, your own PNG slots (UI_ICONS), per-app icon overrides, real installed-app icons. */
(function(){
'use strict';
var G=window.Glass;
var P={
  search:'<circle cx="11" cy="11" r="6.5"/><path d="M16 16l5 5"/>',
  controls:'<rect x="3" y="4" width="18" height="7" rx="3.5"/><circle class="fill" cx="7.5" cy="7.5" r="2"/><rect x="3" y="13" width="18" height="7" rx="3.5"/><circle class="fill" cx="16.5" cy="16.5" r="2"/>',
  wifi:'<path d="M2 9a15 15 0 0 1 20 0"/><path d="M5 12.5a10.5 10.5 0 0 1 14 0"/><path d="M8.5 16a5.5 5.5 0 0 1 7 0"/><circle class="fill" cx="12" cy="19.5" r="1.2"/>',
  wifiOff:'<path d="M2 9a15 15 0 0 1 20 0"/><path d="M5 12.5a10.5 10.5 0 0 1 14 0"/><path d="M8.5 16a5.5 5.5 0 0 1 7 0"/><circle class="fill" cx="12" cy="19.5" r="1.2"/><path d="M3 3l18 18"/>',
  ethernet:'<rect x="5" y="3" width="14" height="10" rx="1.5"/><path d="M9 13v3M15 13v3M12 16v5M8 8h2M14 8h2"/>',
  bluetooth:'<path d="M6.5 7.5l11 9-5.5 4.5V3l5.5 4.5-11 9"/>',
  bluetoothOff:'<path d="M6.5 7.5l11 9-5.5 4.5V3l5.5 4.5-11 9"/><path d="M3 3l18 18"/>',
  volume:'<path d="M4 9v6h4l5 4V5L8 9H4z"/><path d="M16 9a4 4 0 0 1 0 6"/><path d="M18.5 6.5a8 8 0 0 1 0 11"/>',
  volumeMuted:'<path d="M4 9v6h4l5 4V5L8 9H4z"/><path d="M17 9.5l5 5M22 9.5l-5 5"/>',
  themeLight:'<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/>',
  themeDark:'<path d="M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z"/>',
  device:'<rect x="3" y="4.5" width="18" height="12" rx="2"/><path d="M9 20h6M12 16.5V20"/>',
  display:'<rect x="3" y="4.5" width="18" height="12" rx="2"/><path d="M9 20h6M12 16.5V20"/>',
  settings:'<circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="7.2"/><circle cx="12" cy="12" r="9.4" stroke-dasharray="2.6 3.1"/>',
  apps:'<rect x="4" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5"/>',
  storage:'<ellipse cx="12" cy="6" rx="7.5" ry="3"/><path d="M4.5 6v12c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3V6M4.5 12c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3"/>',
  clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
  keyboard:'<rect x="2.5" y="6" width="19" height="12" rx="2"/><path d="M6 10h.5M10 10h.5M14 10h.5M18 10h.5M7 14h10"/>',
  access:'<circle cx="12" cy="4.5" r="1.6"/><path d="M5 8.5l7 1.5 7-1.5M12 10v5l-3 6M12 15l3 6"/>'
};
G.icons={
  has:function(k){ return !!P[k]; },
  svg:function(k){ return '<svg class="gsvg" viewBox="0 0 24 24" aria-hidden="true">'+(P[k]||P.settings)+'</svg>'; },
  /* Fill `holder` with the user's PNG for UI_ICONS[key] if it loads, otherwise the built-in SVG. Re-filling the same key is a no-op. */
  fill:function(holder,key){
    if(holder.getAttribute('data-k')===key) return;
    holder.setAttribute('data-k',key);
    var src=G.cfg.ui[key], fb=function(){ if(holder.getAttribute('data-k')===key) holder.innerHTML=G.icons.svg(key); };
    if(!src){ fb(); return; }
    holder.innerHTML='';
    var im=new Image(); im.className='uiimg'; im.alt=''; im.onerror=fb; im.src=src; holder.appendChild(im);
  },
  /* Custom icon override for a package (visual only: the package and its launch path are untouched). */
  override:function(pkg){ return (pkg&&G.cfg.overrides[pkg])||null; },
  /* Real installed-app icon from Android, cached, delivered asynchronously so the UI never stalls. */
  _cache:{},
  packageIcon:function(pkg,cb){
    var c=G.icons._cache;
    if(c.hasOwnProperty(pkg)){ cb(c[pkg]); return; }
    setTimeout(function(){
      var u=G.bridge.call('getAppIcon',pkg); if(typeof u!=='string'||u.indexOf('data:')!==0) u=null;
      c[pkg]=u; cb(u);
    },0);
  }
};
if(G.cfg.ui.invert===false) document.documentElement.classList.add('noinvert-flag');
})();
