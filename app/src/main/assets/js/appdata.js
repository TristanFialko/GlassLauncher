/* GLASS app discovery: installed-app list from Android. Used by Home rows and Search. */
(function(){
'use strict';
var G=window.Glass;
G.apps={
  installed:[],
  HIDDEN:/^(org\.tizen|com\.samsung|DU\.TV\.PRODA)/i,          // same exclusions the Phase 2 library used
  load:function(){
    var l=G.bridge.json('getInstalledApps'); G.apps.installed=Array.isArray(l)?l:[];
    /* resolve every configured app (top row + Entertainment) to the package that is really installed on this box */
    G.cfg.apps.concat(G.cfg.entertainment.apps||[]).forEach(G.apps.resolve);
    return G.apps.installed;
  },
  /* order: current id if installed > first installed of a.ids > first installed app whose name matches a.match. Never invents an id. */
  resolve:function(a){
    var inst=G.apps.installed, has=function(id){ return inst.some(function(x){return x.id===id;}); }, k, x;
    if(a.id&&has(a.id)) return a;
    var cand=(a.ids||[]); for(k=0;k<cand.length;k++) if(has(cand[k])){ a.id=cand[k]; return a; }
    if(a.match) for(k=0;k<inst.length;k++){ x=inst[k]; if(x.show!==false&&!G.apps.HIDDEN.test(x.id)&&a.match.test(x.name)){ a.id=x.id; return a; } }
    if(!a.id&&cand.length&&!inst.length) a.id=cand[0];   // no app list at all (e.g. a plain browser): keep the first candidate so launching is still attempted
    return a;
  },
  isInstalled:function(pkg){ return G.apps.installed.some(function(x){return x.id===pkg;}); },
  byId:function(pkg){ for(var i=0;i<G.apps.installed.length;i++) if(G.apps.installed[i].id===pkg) return G.apps.installed[i]; return null; }
};
})();
