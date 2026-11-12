/* GLASS app discovery: installed-app list from Android. Used by Home rows and Search. */
(function(){
'use strict';
var G=window.Glass;
G.apps={
  installed:[],
  HIDDEN:/^(org\.tizen|com\.samsung|DU\.TV\.PRODA)/i,          // same exclusions the Phase 2 library used
  load:function(){
    var l=G.bridge.json('getInstalledApps'); G.apps.installed=Array.isArray(l)?l:[];
    /* resolve pinned apps whose package id differs on this firmware (apps.js `match`) */
    G.cfg.apps.forEach(function(a){
      if(!a.id) return;
      var found=G.apps.installed.some(function(x){return x.id===a.id;});
      if(!found) for(var k=0;k<G.apps.installed.length;k++){ var x=G.apps.installed[k]; if(x.show!==false&&a.match&&a.match.test(x.name)){ a.id=x.id; break; } }
    });
    return G.apps.installed;
  },
  isInstalled:function(pkg){ return G.apps.installed.some(function(x){return x.id===pkg;}); },
  byId:function(pkg){ for(var i=0;i<G.apps.installed.length;i++) if(G.apps.installed[i].id===pkg) return G.apps.installed[i]; return null; }
};
})();
