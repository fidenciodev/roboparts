import { useRef, useState } from "react";
import type { FormEvent } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Archive, Bot, ClipboardCheck, GripVertical, Plus, Settings2 } from "lucide-react";
import { Link, useNavigate, useParams } from "react-router";
import * as api from "../services/workspaceService";
import type { NodeDraft, Robot, TreeNode } from "../types/workspace";
import { Feedback, Heading, InlineName, QueryState, useAction } from "../components/WorkspaceUi";
const blank=():NodeDraft=>({kind:"COMPONENT",name:"",description:"",parentId:null,quantity:1,required:true,position:0,archived:false});
function NodeEditor({node,draft,onCancel,onSave,busy}:{node:TreeNode|null;draft:NodeDraft;onCancel:()=>void;onSave:(draft:NodeDraft)=>Promise<boolean>;busy:boolean}) {
 const [value,setValue]=useState(draft);
 async function submit(e:FormEvent){e.preventDefault();await onSave({...value,kind:"COMPONENT",parentId:null,name:value.name.trim(),description:value.description.trim()});}
 return <form className="panel editor-panel" onSubmit={e=>void submit(e)} onKeyDown={e=>{if(e.key==="Escape"&&!busy)onCancel();}}><h2>{node?"Editar componente":"Adicionar componente"}</h2><fieldset disabled={busy}>
  <label>Nome do componente<input autoFocus required minLength={2} maxLength={100} value={value.name} onChange={e=>setValue({...value,name:e.target.value})}/></label>
  <label>Descrição do componente<textarea maxLength={1000} value={value.description} onChange={e=>setValue({...value,description:e.target.value})}/></label>
  <div className="form-grid"><label>Quantidade necessária<input type="number" required min={1} max={1000000} step={1} value={value.quantity} onChange={e=>setValue({...value,quantity:Number(e.target.value)})}/></label>
  {node&&<label>Posição na lista (começa em 1)<input type="number" required min={1} max={10001} value={value.position+1} onChange={e=>setValue({...value,position:Number(e.target.value)-1})}/></label>}</div>
  <label className="check-label"><input type="checkbox" checked={value.required} onChange={e=>setValue({...value,required:e.target.checked})}/> Obrigatório no checklist</label>
  <div className="workspace-actions"><button className="primary-button">Salvar componente</button><button className="secondary-button" type="button" onClick={onCancel}>Cancelar</button></div>
 </fieldset></form>;
}
export function RobotDetailPage() {
 const {id=""}=useParams();const client=useQueryClient();const navigate=useNavigate();const action=useAction();
 const [editor,setEditor]=useState<{node:TreeNode|null;draft:NodeDraft}|null>(null);const [settings,setSettings]=useState(false);const [description,setDescription]=useState("");
 const [showArchived,setShowArchived]=useState(false);
 const startId=useRef<string|null>(null);
 const query=useQuery({queryKey:["workspace","robot",id],queryFn:({signal})=>api.getRobot(id,signal),retry:false});
 async function saved(result:Robot) {client.setQueryData(["workspace","robot",id],result);await client.invalidateQueries({queryKey:["workspace"]});}
 async function update(n:TreeNode,changes:Partial<NodeDraft>){return action.run(async()=>{await saved(await api.updateNode(query.data!,n,{...changes,parentId:null}));});}
 async function start(){if(!query.data)return;startId.current??=crypto.randomUUID();await action.run(async()=>{
  const checklist=await api.startChecklist(query.data!,startId.current!);startId.current=null;await client.invalidateQueries({queryKey:["workspace"]});navigate("/checklists/"+checklist.id);
 },"Checklist iniciado.");}
 function componentList(robot:Robot) {
  return <ul className="component-tree">{robot.nodes.filter(n=>n.kind==="COMPONENT"&&(showArchived||!n.archived)).sort((a,b)=>a.position-b.position||a.id.localeCompare(b.id)).map(n=><li key={n.id}>
   <div className={"tree-row"+(n.archived?" tree-row--archived":"")}>
    <Bot size={18} className="tree-icon"/>
    <div className="tree-name"><InlineName name={n.name} disabled={action.busy||robot.archived||n.archived} onSave={name=>update(n,{name})}/>{n.description&&<p>{n.description}</p>}<span className="tree-meta">{n.archived?"Arquivado":n.quantity+" unidade(s) · "+(n.required?"Obrigatório":"Opcional")}</span></div>
    <div className="tree-actions">
     <button className="icon-button" disabled={action.busy||robot.archived||n.archived} aria-label={"Editar detalhes de "+n.name} onClick={()=>setEditor({node:n,draft:{...n,kind:"COMPONENT",parentId:null}})}><Settings2 size={17}/></button>
     <button className="icon-button" disabled={action.busy||robot.archived} aria-label={(n.archived?"Restaurar ":"Arquivar ")+n.name} onClick={()=>void update(n,{archived:!n.archived})}><Archive size={17}/></button>
    </div>
   </div>
  </li>)}</ul>;
 }
 const r=query.data;
 return <><Link className="back-link" to="/robos">← Todos os robôs</Link><QueryState pending={query.isPending} error={query.isError?query.error:null} retry={()=>void query.refetch()}/>{r&&!query.isError&&<>
  <Heading title={r.name} description={r.description||"Organize a estrutura e confira os componentes antes da retirada."}>
   <button className="secondary-button" disabled={action.busy} onClick={()=>void query.refetch()}>Atualizar</button>
   <button className="primary-button" disabled={action.busy||r.archived||!r.nodes.some(n=>n.kind==="COMPONENT"&&!n.archived)} onClick={()=>void start()}><ClipboardCheck size={17}/> Iniciar retirada</button>
  </Heading><Feedback {...action}/>
  <div className="panel robot-settings"><InlineName name={r.name} disabled={action.busy} onSave={name=>action.run(async()=>{await saved(await api.updateRobot(r,{name}));})}/><span className="state-pill">{r.archived?"Arquivado":"Ativo"}</span>
   <button className="secondary-button" disabled={action.busy} onClick={()=>{setDescription(r.description);setSettings(!settings);}}>Editar descrição</button>
   <button className="secondary-button" disabled={action.busy} onClick={()=>void action.run(async()=>{await saved(await api.updateRobot(r,{archived:!r.archived}));},r.archived?"Robô restaurado.":"Robô arquivado.")}>{r.archived?"Restaurar robô":"Arquivar robô"}</button>
   <Link to={"/checklists?robotId="+r.id}>Ver retiradas anteriores →</Link>
  </div>
  {settings&&<form className="panel editor-panel" onSubmit={e=>{e.preventDefault();void action.run(async()=>{await saved(await api.updateRobot(r,{description:description.trim()}));setSettings(false);});}}><label>Descrição do robô<textarea value={description} maxLength={1000} disabled={action.busy} onChange={e=>setDescription(e.target.value)}/></label><div className="workspace-actions"><button className="primary-button" disabled={action.busy}>Salvar descrição</button><button className="secondary-button" type="button" onClick={()=>setSettings(false)}>Cancelar</button></div></form>}
  {editor&&<NodeEditor key={editor.node?.id??"new-component"} {...editor} busy={action.busy} onCancel={()=>setEditor(null)} onSave={async draft=>{
   const ok=await action.run(async()=>{await saved(editor.node?await api.updateNode(r,editor.node,draft):await api.addNode(r,draft));});if(ok)setEditor(null);return ok;
  }}/>}
  <section className="panel tree-panel" aria-label="Componentes do robô"><div className="panel-heading"><div><p className="eyebrow">LISTA EDITÁVEL</p><h2>Componentes do robô</h2><p>Clique no nome para renomear: Enter salva; Escape cancela. Abra os detalhes para alterar os demais campos.</p></div><div className="workspace-actions"><button className="secondary-button" disabled={action.busy||r.archived} onClick={()=>setEditor({node:null,draft:blank()})}><Plus size={16}/> Adicionar componente</button></div></div>
   <div className="tree-toolbar"><label className="check-label"><input type="checkbox" checked={showArchived} onChange={e=>setShowArchived(e.target.checked)}/> Mostrar arquivados</label><span><GripVertical size={14}/> Reordene nos detalhes do componente.</span></div>
   {r.nodes.some(n=>n.kind==="COMPONENT"&&(showArchived||!n.archived))?componentList(r):<p className="empty-state">Esta lista está vazia. Adicione um componente.</p>}
  </section>
 </>}</>;
}
