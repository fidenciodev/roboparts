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
import { ChecklistsPage } from "../pages/ChecklistsPage";
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
 <Route path="/checklists" element={<ChecklistsPage/>}/><Route path="/checklists/:id" element={<ChecklistDetailPage/>}/><Route path="/historico" element={<HistoryPage/>}/>
 </Routes></MemoryRouter></QueryClientProvider>);return client;
}
beforeEach(()=>invalidateCsrf());
it("mostra componentes antigos em uma lista e edita sem campos de categoria",async()=>{
 let current=structuredClone(robot);
 const mock=api((url,init)=>{
  if(url==="/api/robots/robot1")return response(current);
  if(url==="/api/robots/robot1/nodes/part1"){
   const body=JSON.parse(String(init.body));current={...current,version:2,nodes:current.nodes.map(n=>n.id==="part1"?{...n,...body}:n)};
   return response(current);
  }
  throw Error(url);
 });app("/robos/robot1");
 await screen.findByRole("button",{name:"Bateria original"});
 expect(screen.queryByText("Sistema elétrico")).not.toBeInTheDocument();
 expect(screen.queryByRole("button",{name:/categoria/i})).not.toBeInTheDocument();
 await userEvent.click(screen.getByRole("button",{name:"Editar detalhes de Bateria original"}));
 expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
 await userEvent.type(screen.getByRole("textbox",{name:"Descrição do componente"})," editada");
 await userEvent.click(screen.getByRole("button",{name:"Salvar componente"}));
 await screen.findByText("Recarregável editada");
 expect(JSON.parse(String(mock.mock.calls.find(([url])=>url.endsWith("/nodes/part1"))![1].body)))
  .toMatchObject({kind:"COMPONENT",parentId:null,name:"Bateria original",quantity:2,expectedVersion:1});
});
it("cadastra componentes diretamente no robô sem precisar de categorias",async()=>{
 let current:Robot={...structuredClone(robot),nodes:[]};
 const mock=api((url,init)=>{
  if(url==="/api/robots/robot1")return response(current);
  if(url==="/api/robots/robot1/nodes"){
   const body=JSON.parse(String(init.body));current={...current,version:2,nodes:[{...body,id:"new-component"}]};return response(current);
  }
  throw Error(url);
 });app("/robos/robot1");
 await userEvent.click(await screen.findByRole("button",{name:"Adicionar componente"}));
 await userEvent.type(screen.getByRole("textbox",{name:"Nome do componente"}),"Sensor de luz");
 await userEvent.type(screen.getByRole("textbox",{name:"Descrição do componente"}),"Sensor reserva");
 expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
 await userEvent.click(screen.getByRole("button",{name:"Salvar componente"}));
 await screen.findByRole("button",{name:"Sensor de luz"});
 expect(JSON.parse(String(mock.mock.calls.find(([url])=>url.endsWith("/nodes"))![1].body)))
  .toMatchObject({kind:"COMPONENT",parentId:null,name:"Sensor de luz",description:"Sensor reserva",expectedVersion:1});
});
it("exibe e filtra componentes de checklists antigos sem agrupar por categoria",async()=>{
 api(()=>response(checklist));app("/checklists/check1");
 await screen.findByRole("checkbox",{name:"Conferir Bateria original"});
 expect(screen.queryByText("Sistema elétrico")).not.toBeInTheDocument();
 expect(screen.queryByRole("button",{name:/Expandir|Recolher/})).not.toBeInTheDocument();
 await userEvent.selectOptions(screen.getByRole("combobox",{name:"Exibir"}),"DONE");
 expect(screen.getByText("Nenhum componente neste filtro.")).toBeInTheDocument();
 expect(screen.queryByRole("checkbox",{name:"Conferir Bateria original"})).not.toBeInTheDocument();
 await userEvent.selectOptions(screen.getByRole("combobox",{name:"Exibir"}),"PENDING");
 expect(screen.getByRole("checkbox",{name:"Conferir Bateria original"})).toBeEnabled();
});
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
it("mostra por que um robô em uso não pode ser arquivado e preserva sua estrutura",async()=>{
 const mock=api((_url,init)=>init.method==="POST"?response({code:"robot_in_use"},409):response(robot));
 app("/robos/robot1");
 await userEvent.click(await screen.findByRole("button",{name:"Arquivar robô"}));
 expect(await screen.findByRole("alert")).toHaveTextContent("Este robô está em uso e não pode ser arquivado. Conclua a devolução de todos os componentes antes de arquivar.");
 expect(screen.getByRole("button",{name:"Arquivar robô"})).toBeEnabled();
 expect(screen.getByRole("button",{name:"Bateria original"})).toBeInTheDocument();
 expect(screen.queryByRole("button",{name:"Restaurar robô"})).not.toBeInTheDocument();
 expect(mock.mock.calls.filter(([,init])=>init.method==="POST")).toHaveLength(1);
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
 expect(screen.getByRole("button",{name:"Confirmar retirada"})).toBeDisabled();
 await userEvent.click(screen.getByRole("checkbox",{name:"Conferir Bateria original"}));
 await waitFor(()=>expect(screen.getByRole("button",{name:"Confirmar retirada"})).toBeEnabled());
 await userEvent.selectOptions(screen.getByRole("combobox",{name:"Exibir"}),"DONE");
 await userEvent.click(screen.getByRole("button",{name:"Confirmar retirada"}));
 expect(mock.mock.calls.some(([url])=>url.endsWith("/finalize"))).toBe(false);
 await userEvent.click(screen.getByRole("button",{name:"Confirmar"}));
 await screen.findByText("Em uso");
 expect(screen.getByRole("heading",{name:"Checklist de devolução"})).toBeInTheDocument();
 expect(screen.getByRole("checkbox",{name:"Devolver Bateria original"})).not.toBeChecked();
 expect(screen.getByRole("spinbutton",{name:"Quantidade a devolver de Bateria original"})).toHaveValue(0);
 expect(screen.getByRole("combobox",{name:"Exibir"})).toHaveValue("ALL");
 expect(screen.queryByRole("button",{name:"Cancelar checklist vazio"})).not.toBeInTheDocument();
 const write=mock.mock.calls.find(([url])=>url.endsWith("/movements"))!;
 expect(JSON.parse(String(write[1].body))).toMatchObject({quantity:2,expectedVersion:0,requestId:expect.any(String)});
 expect(screen.queryByRole("button",{name:"Confirmar retirada"})).not.toBeInTheDocument();
});
it("devolve quantidades parciais e itens opcionais até concluir a devolução",async()=>{
 let current:Checklist={...structuredClone(checklist),status:"COMPLETED",version:4,completed:1,pending:0,finalizedAt:date,finalizedByName:"Ana",
  items:[{...checklist.items[1],takenQuantity:2},
   {...checklist.items[1],id:"optional",name:"Cabo opcional",required:false,quantity:3,takenQuantity:1,position:1},
   {...checklist.items[1],id:"unused",name:"Peça não retirada",required:false,takenQuantity:0,position:2}],
  movements:[{id:"withdraw1",itemId:"part1",itemName:"Bateria original",delta:2,resultingQuantity:2,userName:"Ana",createdAt:date},
   {id:"withdraw2",itemId:"optional",itemName:"Cabo opcional",delta:1,resultingQuantity:1,userName:"Ana",createdAt:date}]};
 const mock=api((url,init)=>{
  if(url==="/api/checklists/check1")return response(current);
  if(url.endsWith("/movements")){
   const body=JSON.parse(String(init.body));const id=url.split("/").at(-2)!;
   const old=current.items.find(i=>i.id===id)!;
   const items=current.items.map(i=>i.id===id?{...i,takenQuantity:body.quantity,checkedByName:"Ana",checkedAt:date}:i);
   current={...current,items,version:current.version+1,status:items.every(i=>i.takenQuantity===0)?"RETURNED":"RETURNING",
    movements:[...current.movements,{id:"return"+current.version,itemId:id,itemName:old.name,delta:body.quantity-old.takenQuantity,resultingQuantity:body.quantity,userName:"Ana",createdAt:date}]};
   return response(current);
  }
  throw Error(url);
 });app("/checklists/check1");
 await screen.findByRole("heading",{name:"Checklist de devolução"});
 expect(screen.getByText("Em uso")).toBeInTheDocument();
 expect(screen.queryByText("Peça não retirada")).not.toBeInTheDocument();
 const progress=screen.getByRole("progressbar",{name:"Componentes devolvidos"});
 expect(progress).toHaveAttribute("value","0");expect(progress).toHaveAttribute("max","2");
 const quantity=screen.getByRole("spinbutton",{name:"Quantidade a devolver de Bateria original"});
 expect(quantity).toHaveValue(0);expect(quantity).toHaveAttribute("max","2");
 await userEvent.clear(quantity);await userEvent.type(quantity,"3");
 expect(screen.getAllByRole("button",{name:"Devolver"})[0]).toBeDisabled();
 expect(mock.mock.calls.filter(([,init])=>init.method==="POST")).toHaveLength(0);
 await userEvent.clear(quantity);await userEvent.type(quantity,"1");
 await userEvent.click(screen.getAllByRole("button",{name:"Devolver"})[0]);
 await screen.findByText("Em devolução");
 expect(screen.getByRole("spinbutton",{name:"Quantidade a devolver de Bateria original"})).toHaveValue(0);
 expect(screen.getByRole("checkbox",{name:"Devolver Bateria original"})).not.toBeChecked();
 await userEvent.click(screen.getByRole("checkbox",{name:"Devolver Bateria original"}));
 await waitFor(()=>expect(screen.getByRole("checkbox",{name:"Devolver Bateria original"})).toBeChecked());
 expect(screen.getByRole("checkbox",{name:"Devolver Bateria original"})).toBeDisabled();
 expect(screen.getByRole("progressbar",{name:"Componentes devolvidos"})).toHaveAttribute("value","1");
 await userEvent.selectOptions(screen.getByRole("combobox",{name:"Exibir"}),"PENDING");
 expect(screen.queryByRole("checkbox",{name:"Devolver Bateria original"})).not.toBeInTheDocument();
 await userEvent.click(screen.getByRole("checkbox",{name:"Devolver Cabo opcional"}));
 await screen.findByText("Devolução concluída. Todos os componentes foram devolvidos.");
 expect(screen.getByText("Devolvido")).toBeInTheDocument();
 expect(screen.getByRole("progressbar",{name:"Componentes devolvidos"})).toHaveAttribute("value","2");
 await userEvent.selectOptions(screen.getByRole("combobox",{name:"Exibir"}),"DONE");
 expect(screen.getByRole("checkbox",{name:"Devolver Cabo opcional"})).toBeChecked();
 expect(screen.getByRole("checkbox",{name:"Devolver Cabo opcional"})).toBeDisabled();
 expect(screen.queryByRole("button",{name:"Devolver"})).not.toBeInTheDocument();
 const writes=mock.mock.calls.filter(([url])=>url.endsWith("/movements"));
 expect(writes.map(([,init])=>JSON.parse(String(init.body)))).toMatchObject([
  {quantity:1,expectedVersion:4},{quantity:0,expectedVersion:5},{quantity:0,expectedVersion:6}]);
});
it("mantém a retirada aberta se o servidor recusa a confirmação",async()=>{
 const current:Checklist={...structuredClone(checklist),status:"IN_PROGRESS",completed:1,pending:0,items:checklist.items.map(i=>i.kind==="COMPONENT"?{...i,takenQuantity:2}:i)};
 const mock=api((url)=>url.endsWith("/finalize")?response({code:"concurrent_update"},409):response(current));
 app("/checklists/check1");
 await userEvent.click(await screen.findByRole("button",{name:"Confirmar retirada"}));
 await userEvent.click(screen.getByRole("button",{name:"Confirmar"}));
 expect(await screen.findByRole("alert")).toHaveTextContent("Outra operação alterou");
 expect(screen.getByText("Em andamento")).toBeInTheDocument();
 expect(screen.queryByRole("heading",{name:"Checklist de devolução"})).not.toBeInTheDocument();
 expect(screen.getByRole("checkbox",{name:"Conferir Bateria original"})).toBeChecked();
 expect(mock.mock.calls.filter(([url])=>url.endsWith("/finalize"))).toHaveLength(1);
});
it("lista o robô em uso com acesso ao checklist de devolução",async()=>{
 api(()=>response([{...checklist,status:"COMPLETED",finalizedAt:date,finalizedByName:"Ana"}]));app("/checklists");
 const card=await screen.findByRole("link",{name:/Abrir checklist de devolução/});
 expect(card).toHaveTextContent("Em uso");expect(card).toHaveAttribute("href","/checklists/check1");
 expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
 expect(screen.getByRole("option",{name:"Em uso"})).toHaveValue("COMPLETED");
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
