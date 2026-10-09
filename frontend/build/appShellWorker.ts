import { createHash } from "node:crypto";
import type { Plugin } from "vite";
// Cache only the application shell and public build assets. Session/API data is never cached.
export function appShellWorker(): Plugin {
 return {
  name:"roboparts-app-shell", apply:"build", enforce:"post",
  generateBundle(_options,bundle) {
   const assets=["/index.html","/manifest.webmanifest","/favicon.svg","/icon-maskable.svg",...Object.keys(bundle).filter(p=>/\.(js|css)$/.test(p)).map(p=>"/"+p)];
   const version=createHash("sha256").update(assets.join("|")).digest("hex").slice(0,16);
   const source=`const CACHE="roboparts-shell-${version}";
const ASSETS=${JSON.stringify(assets)};
self.addEventListener("install",event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(ASSETS))));
self.addEventListener("activate",event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key.startsWith("roboparts-shell-")&&key!==CACHE).map(key=>caches.delete(key))))));
self.addEventListener("fetch",event=>{
 const url=new URL(event.request.url);
 if(event.request.method!=="GET"||url.origin!==self.location.origin)return;
 // Business responses, authentication and cookies are never persisted by this worker.
 if(url.pathname.startsWith("/api/")||url.pathname.startsWith("/actuator/"))return;
 if(event.request.mode==="navigate"){event.respondWith(fetch(event.request).catch(()=>caches.open(CACHE).then(cache=>cache.match("/index.html"))));return;}
 if(!ASSETS.includes(url.pathname))return;
 event.respondWith(caches.open(CACHE).then(async cache=>(await cache.match(url.pathname))||fetch(event.request)));
});`;
   this.emitFile({type:"asset",fileName:"sw.js",source});
  }
 };
}
