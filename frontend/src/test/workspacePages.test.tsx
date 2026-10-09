import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, expect, it, vi } from "vitest";
import { invalidateCsrf } from "../services/api";
import { RobotDetailPage } from "../pages/RobotDetailPage";
import { ChecklistDetailPage } from "../pages/ChecklistDetailPage";
import { HistoryPage } from "../pages/HistoryPage";
import { RobotsPage } from "../pages/RobotsPage";
import type { Robot, Checklist } from "../types/workspace";
const date="2026-10-09T12:00:00Z";
const robot:Robot={id:"robot1",name:"Robô RX",description:"Descrição",archived:false,version:1,createdAt:date,nodes:[
 {id:"cat1",parentId:null,kind:"CATEGORY",name:"Sistema elétrico",description:"",quantity:1,required:true,position:0,archived:false},
 {id:"part1",parentId:"cat1",kind:"COMPONENT",name:"Bateria original",description:"Recarregável",quantity:2,required:true,position:0,archived:false}
]};
const checklist:Checklist={id:"check1",robotId:"robot1",robotName:"Nome preservado",robotDescription:"",status:"PENDING",version:0,startedBy:"user1",startedByName:"Ana",createdAt:date,finalizedAt:null,finalizedByName:null,total:1,completed:0,pending:1,
 items:robot.nodes.map(n=>({...n,takenQuantity:0,checkedByName:null,checkedAt:null})),movements:[]};
const response=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status});
function api(handler:(url:string,init:RequestInit)=>Response|Promise<Response>){
 const mock=vi.fn((url:string,init:RequestInit)=>url==="/api/auth/csrf"?Promise.resolve(response({token:"safe-csrf",headerName:"X-CSRF-TOKEN"})):Promise.resolve(handler(url,init)));
 vi.stubGlobal("fetch",mock);return mock;
}
function app(path:string){
 const client=new QueryClient({defaultOptions:{queries:{retry:false,gcTime:0}}});
 render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
 <Route path="/robos" element={<RobotsPage/>}/><Route path="/robos/:id" element={<RobotDetailPage/>}/>
 <Route path="/checklists/:id" element={<ChecklistDetailPage/>}/><Route path="/historico" element={<HistoryPage/>}/>
 </Routes></MemoryRouter></QueryClientProvider>);return client;
}
beforeEach(()=>invalidateCsrf());
it("edita inline com Enter, cancela com Escape e salva na API protegida",async()=>{
 let current=structuredClone(robot);
 const mock=api((url,init)=>{
  if(url==="/api/robots/robot1")return response(current);
  if(url==="/api/robots/robot1/nodes/part1"){const body=JSON.parse(String(init.body));current={...current,version:2,nodes:current.nodes.map(n=>n.id==="part1"?{...n,name:body.name}:n)};return response(current);}
  throw Error(url);
 });app("/robos/robot1");
 await userEvent.click(await screen.findByRole("button",{name:"Bateria original"}));
 const input=screen.getByRole("textbox",{name:"Novo nome de Bateria original"});
 await userEvent.clear(input);await userEvent.type(input,"Cancelada");await userEvent.keyboard("{Escape}");
 expect(screen.getByRole("button",{name:"Bateria original"})).toBeInTheDocument();
 expect(mock.mock.calls.filter(([,init])=>init.method==="POST")).toHaveLength(0);
 await userEvent.click(screen.getByRole("button",{name:"Bateria original"}));
 const edit=screen.getByRole("textbox",{name:"Novo nome de Bateria original"});await userEvent.clear(edit);await userEvent.type(edit,"Bateria editada{Enter}");
 await screen.findByRole("button",{name:"Bateria editada"});
 const write=mock.mock.calls.find(([url])=>url.endsWith("/nodes/part1"))!;
 expect(JSON.parse(String(write[1].body))).toMatchObject({name:"Bateria editada",expectedVersion:1});
 expect(write[1]).toMatchObject({credentials:"include",headers:{"X-CSRF-TOKEN":"safe-csrf"}});
});
it("mantém um erro de concorrência visível e não repete o salvamento",async()=>{
 const mock=api((url,init)=>init.method==="POST"?response({code:"concurrent_update"},409):url==="/api/robots/robot1"?response(robot):response([]));
 app("/robos/robot1");await userEvent.click(await screen.findByRole("button",{name:"Bateria original"}));
 await userEvent.type(screen.getByRole("textbox",{name:"Novo nome de Bateria original"}),"X{Enter}");
 expect(await screen.findByRole("alert")).toHaveTextContent("Outra operação alterou");
 expect(mock.mock.calls.filter(([,init])=>init.method==="POST")).toHaveLength(1);
 expect(screen.getByRole("textbox",{name:"Novo nome de Bateria original"})).toBeInTheDocument();
});
it("confere uma retirada, mantém o snapshot e pede confirmação para finalizar",async()=>{
 let current=structuredClone(checklist);
 const mock=api((url,init)=>{
  if(url==="/api/checklists/check1")return response(current);
  if(url.endsWith("/movements")){current={...current,status:"IN_PROGRESS",version:1,completed:1,pending:0,items:current.items.map(i=>i.id==="part1"?{...i,takenQuantity:2,checkedByName:"Ana",checkedAt:date}:i)};return response(current);}
  if(url.endsWith("/finalize")){current={...current,status:"COMPLETED",version:2,finalizedAt:date,finalizedByName:"Ana"} as typeof current;return response(current);}
  throw Error(String(init.method)+url);
 });app("/checklists/check1");
 expect(await screen.findByRole("heading",{name:/Nome preservado · Retirada/})).toBeInTheDocument();
 expect(screen.getByRole("button",{name:"Finalizar conferência"})).toBeDisabled();
 await userEvent.click(screen.getByRole("checkbox",{name:"Conferir Bateria original"}));
 await waitFor(()=>expect(screen.getByRole("button",{name:"Finalizar conferência"})).toBeEnabled());
 await userEvent.click(screen.getByRole("button",{name:"Finalizar conferência"}));
 expect(mock.mock.calls.some(([url])=>url.endsWith("/finalize"))).toBe(false);
 await userEvent.click(screen.getByRole("button",{name:"Confirmar"}));
 await screen.findByText("Retirada conferida");
 const write=mock.mock.calls.find(([url])=>url.endsWith("/movements"))!;
 expect(JSON.parse(String(write[1].body))).toMatchObject({quantity:2,expectedVersion:0,requestId:expect.any(String)});
 expect(screen.getByRole("button",{name:"Finalizar conferência"})).toBeDisabled();
});
it("cadastra um robô e mostra apenas o resultado recebido da API",async()=>{
 let list:typeof robot[]=[];
 api((url,init)=>{
  if(url.startsWith("/api/robots?page="))return response(list);
  if(url==="/api/robots"&&init.method==="POST"){list=[{...robot,name:JSON.parse(String(init.body)).name,nodes:[]}];return response(list[0],201);}
  throw Error(url);
 });app("/robos");
 await screen.findByRole("heading",{name:"Nenhum robô nesta página."});
 await userEvent.click(screen.getByRole("button",{name:"Adicionar robô"}));
 await userEvent.type(screen.getByLabelText("Nome do robô"),"Robô novo");
 await userEvent.click(screen.getByRole("button",{name:"Salvar robô"}));
 await screen.findByRole("heading",{name:"Robô novo"});
 expect(screen.queryByRole("heading",{name:"Cadastrar robô"})).not.toBeInTheDocument();
});
it("explica o bloqueio do cancelamento e habilita registrar quando a quantidade muda",async()=>{
 const current={...checklist,status:"IN_PROGRESS" as const,completed:1,pending:0,
  items:checklist.items.map(i=>i.id==="part1"?{...i,takenQuantity:2}:i)};
 const mock=api(()=>response(current));app("/checklists/check1");
 const registered=await screen.findByRole("button",{name:"Registrado"});
 expect(registered).toBeDisabled();
 expect(registered).toHaveAttribute("title","Altere a quantidade para registrar uma movimentação.");
 const cancel=screen.getByRole("button",{name:"Cancelar checklist vazio"});
 expect(cancel).toBeDisabled();
 expect(cancel).toHaveAccessibleDescription("Para cancelar, devolva os componentes retirados, ajustando as quantidades para zero.");
 const quantity=screen.getByRole("spinbutton",{name:"Quantidade retirada de Bateria original"});
 await userEvent.clear(quantity);await userEvent.type(quantity,"1");
 expect(screen.getByRole("button",{name:"Registrar"})).toBeEnabled();
 expect(mock.mock.calls.filter(([,init])=>init.method==="POST")).toHaveLength(0);
});
it("mostra auditoria com responsável e permite filtrar as próprias atividades",async()=>{
 const entry={id:"event1",userId:"user1",userName:"Ana",action:"NODE_UPDATED",details:"Renomeou Bateria",resourceType:"ROBOT",resourceId:"robot1",createdAt:date};
 const mock=api(()=>response([entry]));app("/historico");
 await screen.findByText("Renomeou Bateria");expect(screen.getByText("Ana")).toBeInTheDocument();
 expect(screen.getByRole("link",{name:"Abrir robô →"})).toHaveAttribute("href","/robos/robot1");
 await userEvent.click(screen.getByRole("checkbox",{name:"Apenas minhas atividades"}));
 await waitFor(()=>expect(mock.mock.calls.some(([url])=>url.endsWith("mine=true"))).toBe(true));
});
