import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { Link, useParams } from "react-router";
import * as api from "../services/workspaceService";
import type { Checklist, ChecklistItem } from "../types/workspace";
import { Feedback, formatDate, Heading, Progress, QueryState, statusLabels, useAction } from "../components/WorkspaceUi";
function ItemQuantity({item,disabled,save,returned}:{item:ChecklistItem;disabled:boolean;save:(quantity:number)=>Promise<boolean>;returned:boolean}) {
 const [quantity,setQuantity]=useState(returned?0:item.takenQuantity);
 const unchanged=returned?quantity===0:quantity===item.takenQuantity;
 const max=returned?item.takenQuantity:item.quantity;
 const valid=Number.isSafeInteger(quantity)&&quantity>=0&&quantity<=max;
 return <form className="quantity-editor" onSubmit={e=>{e.preventDefault();if(!disabled&&!unchanged&&valid)void save(returned?item.takenQuantity-quantity:quantity);}}>
  <input aria-label={(returned?"Quantidade a devolver de ":"Quantidade retirada de ")+item.name} type="number" min={0} max={max} required step={1} disabled={disabled} value={quantity} onChange={e=>setQuantity(Number(e.target.value))}/>
  <span>/ {max}</span><button className="secondary-button" disabled={disabled||unchanged||!valid} title={unchanged?(returned?"Informe a quantidade que está devolvendo.":"Altere a quantidade para registrar uma movimentação."):undefined}>{returned?"Devolver":unchanged&&item.takenQuantity>0?"Registrado":"Registrar"}</button>
 </form>;
}
export function ChecklistDetailPage() {
 const {id=""}=useParams();const client=useQueryClient();const action=useAction();const [filter,setFilter]=useState("ALL");
 const [confirmation,setConfirmation]=useState<"finalize"|"cancel"|null>(null);
 const pending=useRef<{itemId:string;quantity:number;requestId:string}|null>(null);
 const query=useQuery({queryKey:["workspace","checklist",id],queryFn:({signal})=>api.getChecklist(id,signal),retry:false});
 const c=query.data;
 const returning=c?.finalizedAt!=null;
 const previouslyTaken=new Set(c?.movements.filter(m=>m.delta>0).map(m=>m.itemId));
 const returnItems=c?.items.filter(i=>i.kind==="COMPONENT"&&(i.required||i.takenQuantity>0||previouslyTaken.has(i.id)))??[];
 const returnedCount=returnItems.filter(i=>i.takenQuantity===0).length;
 const cancelReason=c?.status==="CANCELLED"?"Este checklist já foi cancelado.":c?.items.some(i=>i.takenQuantity>0)?"Para cancelar, devolva os componentes retirados, ajustando as quantidades para zero.":null;
 async function saved(result:Checklist){client.setQueryData(["workspace","checklist",id],result);await client.invalidateQueries({queryKey:["workspace"]});}
 async function move(itemId:string,quantity:number) {
  if(!c)return false;
  if(!pending.current||pending.current.itemId!==itemId||pending.current.quantity!==quantity)pending.current={itemId,quantity,requestId:crypto.randomUUID()};
  const ok=await action.run(async()=>{await saved(await api.moveItem(c,itemId,quantity,pending.current!.requestId));},returning?"Devolução registrada.":"Movimentação registrada.");
  if(ok)pending.current=null;return ok;
 }
 function componentList() {
  const complete=(i:ChecklistItem)=>returning?i.takenQuantity===0:i.takenQuantity===i.quantity;
  const visible=(returning?returnItems:c!.items.filter(i=>i.kind==="COMPONENT")).filter(i=>filter==="ALL"||(filter==="PENDING"?!complete(i):complete(i))).sort((a,b)=>a.position-b.position||a.id.localeCompare(b.id));
  if(!visible.length)return <p className="empty-state">Nenhum componente neste filtro.</p>;
  return <ul className="component-tree checklist-tree">{visible.map(i=>{
   const checked=complete(i);
   const disabled=action.busy||c!.status==="CANCELLED"||c!.status==="RETURNED";
   return <li key={i.id}><div className={"tree-row "+(checked?"tree-row--checked":"")}>
     <input className="item-check" type="checkbox" aria-label={(returning?"Devolver ":"Conferir ")+i.name} disabled={disabled||(returning&&checked)} checked={checked} onChange={e=>void move(i.id,returning?0:e.target.checked?i.quantity:0)}/>
    <div className="tree-name"><strong>{i.name}</strong>{i.description&&<p>{i.description}</p>}
     <p className="tree-meta">{returning?(checked?"Devolvido":i.takenQuantity+" unidade(s) a devolver"):(i.required?"Obrigatório":"Opcional")+" · "+i.takenQuantity+"/"+i.quantity+" retirado(s)"}{i.checkedByName&&<> · {i.checkedByName} · {formatDate(i.checkedAt!)}</>}</p>
    </div>
    {(!returning||!checked)&&<ItemQuantity key={i.id+"-"+i.takenQuantity+"-"+returning} item={i} disabled={disabled} returned={returning} save={quantity=>move(i.id,quantity)}/>}
   </div></li>;
  })}</ul>;
 }
 return <><Link className="back-link" to="/checklists">← Retiradas e devoluções</Link><QueryState pending={query.isPending} error={query.isError?query.error:null} retry={()=>void query.refetch()}/>{c&&!query.isError&&<>
  <Heading title={c.robotName+(returning?" · Devolução #":" · Retirada #")+c.id.slice(0,8)} description={"Retirada iniciada em "+formatDate(c.createdAt)+" por "+c.startedByName}>
   <button className="secondary-button" disabled={action.busy||query.isFetching} onClick={()=>void query.refetch()}><RefreshCw size={16}/>Atualizar</button><span className={"state-pill state-"+c.status.toLowerCase()}>{statusLabels[c.status]}</span>
  </Heading><Feedback {...action}/>
  <section className="panel checklist-summary"><Progress completed={returning?returnedCount:c.completed} total={returning?returnItems.length:c.total} label={returning?"Componentes devolvidos":"Componentes obrigatórios conferidos"}/>
   <p>{returning?(c.status==="RETURNED"?"Devolução concluída. Todos os componentes foram devolvidos.":"Restam "+(returnItems.length-returnedCount)+" componente(s) a devolver. Marque um componente para devolver todas as unidades restantes."):"Restam "+c.pending+" componente(s) obrigatório(s). Cada movimentação registra o responsável."}</p>
   {returning&&<p>Retirada confirmada em {formatDate(c.finalizedAt!)} por {c.finalizedByName}.</p>}
   <div className="workspace-actions">{!returning&&<><button className="primary-button" disabled={action.busy||c.pending>0||c.status==="CANCELLED"||!c.items.some(i=>i.takenQuantity>0)} onClick={()=>setConfirmation("finalize")}>Confirmar retirada</button>
   <button className="secondary-button" disabled={action.busy||cancelReason!==null} title={cancelReason??undefined} aria-describedby={cancelReason?"cancel-checklist-help":undefined} onClick={()=>setConfirmation("cancel")}>Cancelar checklist vazio</button></>}<Link to={"/robos/"+c.robotId}>Ver estrutura atual →</Link></div>
   {!returning&&cancelReason&&<p id="cancel-checklist-help" className="muted-text">{cancelReason}</p>}
  </section>
  {confirmation&&!returning&&<section className="panel confirmation-panel" aria-label="Confirmar encerramento"><h2>{confirmation==="finalize"?"Confirmar a retirada deste robô?":"Cancelar este checklist?"}</h2><p>{confirmation==="finalize"?"O robô ficará em uso e esta tela abrirá o checklist de devolução.":"O histórico será preservado. Uma próxima retirada precisa de um novo checklist."}</p><div className="workspace-actions"><button className="primary-button" disabled={action.busy} onClick={()=>void action.run(async()=>{await saved(confirmation==="finalize"?await api.finalizeChecklist(c):await api.cancelChecklist(c));setFilter("ALL");setConfirmation(null);},confirmation==="finalize"?"Retirada confirmada. Robô em uso. Checklist de devolução disponível.":"Checklist cancelado.")}>Confirmar</button><button className="secondary-button" disabled={action.busy} onClick={()=>setConfirmation(null)}>Voltar</button></div></section>}
  <section className="panel tree-panel"><div className="panel-heading"><div><p className="eyebrow">{returning?"COMPONENTES PARA DEVOLUÇÃO":"COMPONENTES DESTA RETIRADA"}</p><h2>{returning?"Checklist de devolução":"Conferência dos componentes"}</h2><p>{returning?"Marque as peças devolvidas ou informe a quantidade que está devolvendo e clique em Devolver.":"Os nomes e quantidades foram preservados no início deste checklist."}</p></div><label>Exibir<select value={filter} onChange={e=>setFilter(e.target.value)}><option value="ALL">Todos os componentes</option><option value="PENDING">{returning?"A devolver":"Pendentes"}</option><option value="DONE">{returning?"Devolvidos":"Conferidos"}</option></select></label></div>{componentList()}</section>
  <section className="panel history-panel"><div className="panel-heading"><div><p className="eyebrow">RASTREABILIDADE</p><h2>Retiradas e devoluções</h2></div></div>{c.movements.length?<ul className="history-list">{[...c.movements].reverse().map(m=><li key={m.id}><span className="history-marker"/><div><strong>{m.userName}</strong><p>{m.delta>0?"Retirou":"Devolveu"} {Math.abs(m.delta)} × {m.itemName}</p><small>Quantidade retirada após a operação: {m.resultingQuantity}</small></div><time dateTime={m.createdAt}>{formatDate(m.createdAt)}</time></li>)}</ul>:<p className="empty-state">Nenhuma movimentação registrada.</p>}</section>
 </>}</>;
}
