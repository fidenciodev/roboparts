import { runInNewContext } from "node:vm";
import { expect, it, vi } from "vitest";
import { appShellWorker } from "../../build/appShellWorker";
function worker() {
 let source="";
 const hook=appShellWorker().generateBundle!;
 const generate=typeof hook==="function"?hook:hook.handler;
 Reflect.apply(generate,{emitFile:(asset:{source:string})=>{source=asset.source;}},[{},{"assets/app.js":{},"assets/app.css":{}}]);
 const handlers:Record<string,(event:unknown)=>void>={};
 const match=vi.fn(async()=>new Response("public shell"));const addAll=vi.fn(async()=>{});
 const fetch=vi.fn(async()=>new Response("network"));const remove=vi.fn(async()=>true);
 const caches={open:vi.fn(async()=>({match,addAll})),keys:vi.fn(async()=>["roboparts-shell-old","other-application-cache"]),delete:remove};
 runInNewContext(source,{self:{location:{origin:"https://roboparts.test"},addEventListener:(name:string,callback:(event:unknown)=>void)=>{handlers[name]=callback;}},caches,fetch,URL});
 return {handlers,match,addAll,fetch,remove,caches};
}
it("não intercepta API, autenticação, requisições externas ou escritas",()=>{
 const w=worker();
 for(const [url,method] of [["https://roboparts.test/api/auth/me","GET"],["https://roboparts.test/api/checklists","GET"],["https://api.other.test/api/robots","GET"],["https://roboparts.test/index.html","POST"]]){
  const respondWith=vi.fn();w.handlers.fetch({request:{url,method,mode:"cors"},respondWith});
  expect(respondWith).not.toHaveBeenCalled();
 }
 expect(w.caches.open).not.toHaveBeenCalled();expect(w.fetch).not.toHaveBeenCalled();
});
it("guarda somente arquivos públicos e remove apenas caches próprios",async()=>{
 const w=worker();let done=Promise.resolve();
 w.handlers.install({waitUntil:(task:Promise<void>)=>{done=task;}});await done;
 expect(w.addAll).toHaveBeenCalledWith(["/index.html","/manifest.webmanifest","/favicon.svg","/icon-maskable.svg","/assets/app.js","/assets/app.css"]);
 w.handlers.activate({waitUntil:(task:Promise<void>)=>{done=task;}});await done;
 expect(w.remove).toHaveBeenCalledExactlyOnceWith("roboparts-shell-old");
});
it("usa o shell público quando a navegação falha sem guardar respostas privadas",async()=>{
 const w=worker();w.fetch.mockRejectedValue(new TypeError("offline"));let done:Promise<Response>|undefined;
 w.handlers.fetch({request:{url:"https://roboparts.test/checklists/123",method:"GET",mode:"navigate"},respondWith:(response:Promise<Response>)=>{done=response;}});
 expect(await (await done!).text()).toBe("public shell");expect(w.match).toHaveBeenCalledWith("/index.html");
 expect(w.addAll).not.toHaveBeenCalled();
});
