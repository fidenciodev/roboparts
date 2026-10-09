import { useRef, useState } from "react";
import type { ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, Folder, RefreshCw } from "lucide-react";
import { Link, useParams } from "react-router";
import * as api from "../services/workspaceService";
import type { Checklist, ChecklistItem } from "../types/workspace";
import { Feedback, formatDate, Heading, Progress, QueryState, statusLabels, useAction } from "../components/WorkspaceUi";
function ItemQuantity({item,disabled,save,returned}:{item:ChecklistItem;disabled:boolean;save:(quantity:number)=>Promise<boolean>;returned:boolean}) {
 const [quantity,setQuantity]=useState(item.takenQuantity);
 const unchanged=quantity===item.takenQuantity;
 return <form className="quantity-editor" onSubmit={e=>{e.preventDefault();if(!disabled&&!unchanged)void save(quantity);}}>
  <input aria-label={"Quantidade retirada de "+item.name} type="number" min={0} max={returned?item.takenQuantity:item.quantity} required step={1} disabled={disabled} value={quantity} onChange={e=>setQuantity(Number(e.target.value))}/>
  <span>/ {item.quantity}</span><button className="secondary-button" disabled={disabled||unchanged} title={unchanged?"Altere a quantidade para registrar uma movimentação.":undefined}>{unchanged&&item.takenQuantity>0?"Registrado":"Registrar"}</button>
 </form>;
}
export function ChecklistDetailPage() {
 const {id=""}=useParams();const client=useQueryClient();const action=useAction();const [filter,setFilter]=useState("ALL");const [collapsed,setCollapsed]=useState<Set<string>>(new Set());
 const [confirmation,setConfirmation]=useState<"finalize"|"cancel"|null>(null);
 const pending=useRef<{itemId:string;quantity:number;requestId:string}|null>(null);
 const query=useQuery({queryKey:["workspace","checklist",id],queryFn:({signal})=>api.getChecklist(id,signal),retry:false});
 const c=query.data;
 const cancelReason=c?.status==="CANCELLED"?"Este checklist já foi cancelado.":c?.finalizedAt?"A conferência já foi finalizada. Registre as devoluções nos componentes.":c?.items.some(i=>i.takenQuantity>0)?"Para cancelar, devolva os componentes retirados, ajustando as quantidades para zero.":null;
 async function saved(result:Checklist){client.setQueryData(["workspace","checklist",id],result);await client.invalidateQueries({queryKey:["workspace"]});}
 async function move(itemId:string,quantity:number) {
  if(!c)return false;
  if(!pending.current||pending.current.itemId!==itemId||pending.current.quantity!==quantity)pending.current={itemId,quantity,requestId:crypto.randomUUID()};
  const ok=await action.run(async()=>{await saved(await api.moveItem(c,itemId,quantity,pending.current!.requestId));},"Movimentação registrada.");
  if(ok)pending.current=null;return ok;
 }
 function toggle(id:string){setCollapsed(old=>{const next=new Set(old);if(next.has(id))next.delete(id);else next.add(id);return next;});}
 function descendants(parent:string):ChecklistItem[] {const result:ChecklistItem[]=[];const queue=[parent];const seen=new Set<string>();while(queue.length){const p=queue.shift()!;if(seen.has(p))continue;seen.add(p);for(const i of c!.items)if(i.parentId===p){result.push(i);if(i.kind==="CATEGORY")queue.push(i.id);}}return result;}
 function tree(parent:string|null,depth=0):ReactNode {
  return <ul className="component-tree checklist-tree">{c!.items.filter(i=>i.parentId===parent).sort((a,b)=>a.position-b.position||a.id.localeCompare(b.id)).map(i=>{
   const isCategory=i.kind==="CATEGORY";const complete=i.takenQuantity===i.quantity;
   if(!isCategory&&((filter==="PENDING"&&complete)||(filter==="DONE"&&!complete)))return null;
   const group=isCategory?descendants(i.id).filter(x=>x.kind==="COMPONENT"&&x.required):[];
   const disabled=action.busy||c!.status==="CANCELLED"||c!.status==="RETURNED";
   return <li key={i.id}><div className={"tree-row "+(!isCategory&&complete?"tree-row--checked":"")}>
    {isCategory?<button className="icon-button" aria-label={(collapsed.has(i.id)?"Expandir ":"Recolher ")+i.name} onClick={()=>toggle(i.id)}>{collapsed.has(i.id)?<ChevronRight size={18}/>:<ChevronDown size={18}/>}</button>:
     <input className="item-check" type="checkbox" aria-label={"Conferir "+i.name} disabled={disabled||(c!.finalizedAt!==null&&!complete)} checked={complete} onChange={e=>void move(i.id,e.target.checked?i.quantity:0)}/>}
    {isCategory&&<Folder size={19}/>}<div className="tree-name"><strong>{i.name}</strong>{i.description&&<p>{i.description}</p>}
     {isCategory?<Progress completed={group.filter(x=>x.takenQuantity===x.quantity).length} total={group.length} label={"Progresso de "+i.name}/>:
     <p className="tree-meta">{i.required?"Obrigatório":"Opcional"} · {i.takenQuantity}/{i.quantity} retirado(s){i.checkedByName&&<> · {i.checkedByName} · {formatDate(i.checkedAt!)}</>}</p>}
    </div>
    {!isCategory&&<ItemQuantity key={i.id+"-"+i.takenQuantity} item={i} disabled={disabled} returned={c!.finalizedAt!==null} save={quantity=>move(i.id,quantity)}/>}
   </div>{isCategory&&!collapsed.has(i.id)&&depth<33&&tree(i.id,depth+1)}</li>;
  })}</ul>;
 }
 return <><Link className="back-link" to="/checklists">← Checklists e retiradas</Link><QueryState pending={query.isPending} error={query.isError?query.error:null} retry={()=>void query.refetch()}/>{c&&!query.isError&&<>
  <Heading title={c.robotName+" · Retirada #"+c.id.slice(0,8)} description={"Iniciada em "+formatDate(c.createdAt)+" por "+c.startedByName}>
   <button className="secondary-button" disabled={action.busy||query.isFetching} onClick={()=>void query.refetch()}><RefreshCw size={16}/>Atualizar</button><span className={"state-pill state-"+c.status.toLowerCase()}>{statusLabels[c.status]}</span>
  </Heading><Feedback {...action}/>
  <section className="panel checklist-summary"><Progress completed={c.completed} total={c.total} label="Componentes obrigatórios conferidos"/>
   <p>{c.finalizedAt?"Conferência finalizada em "+formatDate(c.finalizedAt)+" por "+c.finalizedByName+". Devoluções continuam disponíveis.":"Restam "+c.pending+" componente(s) obrigatório(s). Cada movimentação registra o responsável."}</p>
   <div className="workspace-actions"><button className="primary-button" disabled={action.busy||c.pending>0||c.finalizedAt!==null||c.status==="CANCELLED"||!c.items.some(i=>i.takenQuantity>0)} onClick={()=>setConfirmation("finalize")}>Finalizar conferência</button>
   <button className="secondary-button" disabled={action.busy||cancelReason!==null} title={cancelReason??undefined} aria-describedby={cancelReason?"cancel-checklist-help":undefined} onClick={()=>setConfirmation("cancel")}>Cancelar checklist vazio</button><Link to={"/robos/"+c.robotId}>Ver estrutura atual →</Link></div>
   {cancelReason&&<p id="cancel-checklist-help" className="muted-text">{cancelReason}</p>}
  </section>
  {confirmation&&<section className="panel confirmation-panel" aria-label="Confirmar encerramento"><h2>{confirmation==="finalize"?"Finalizar esta conferência?":"Cancelar este checklist?"}</h2><p>{confirmation==="finalize"?"A retirada ficará registrada como conferida. Depois, você poderá registrar apenas devoluções.":"O histórico será preservado. Uma próxima retirada precisa de um novo checklist."}</p><div className="workspace-actions"><button className="primary-button" disabled={action.busy} onClick={()=>void action.run(async()=>{await saved(confirmation==="finalize"?await api.finalizeChecklist(c):await api.cancelChecklist(c));setConfirmation(null);},"Checklist encerrado.")}>Confirmar</button><button className="secondary-button" disabled={action.busy} onClick={()=>setConfirmation(null)}>Voltar</button></div></section>}
  <section className="panel tree-panel"><div className="panel-heading"><div><p className="eyebrow">ESTRUTURA DESTA RETIRADA</p><h2>Conferência dos componentes</h2><p>Os nomes e quantidades foram preservados no início deste checklist.</p></div><label>Exibir<select value={filter} onChange={e=>setFilter(e.target.value)}><option value="ALL">Todos os componentes</option><option value="PENDING">Pendentes</option><option value="DONE">Conferidos</option></select></label></div>{tree(null)}</section>
  <section className="panel history-panel"><div className="panel-heading"><div><p className="eyebrow">RASTREABILIDADE</p><h2>Retiradas e devoluções</h2></div></div>{c.movements.length?<ul className="history-list">{[...c.movements].reverse().map(m=><li key={m.id}><span className="history-marker"/><div><strong>{m.userName}</strong><p>{m.delta>0?"Retirou":"Devolveu"} {Math.abs(m.delta)} × {m.itemName}</p><small>Quantidade retirada após a operação: {m.resultingQuantity}</small></div><time dateTime={m.createdAt}>{formatDate(m.createdAt)}</time></li>)}</ul>:<p className="empty-state">Nenhuma movimentação registrada.</p>}</section>
 </>}</>;
}
