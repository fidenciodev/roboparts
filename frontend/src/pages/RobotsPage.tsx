import { useState } from "react";
import type { FormEvent } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot, Plus, Search } from "lucide-react";
import { Link } from "react-router";
import * as api from "../services/workspaceService";
import { Feedback, Heading, Pagination, QueryState, useAction } from "../components/WorkspaceUi";
export function RobotsPage() {
 const [page,setPage]=useState(0);const [search,setSearch]=useState("");const [archived,setArchived]=useState(false);const [creating,setCreating]=useState(false);
 const [name,setName]=useState("");const [description,setDescription]=useState("");const action=useAction();const client=useQueryClient();
 const query=useQuery({queryKey:["workspace","robots",page],queryFn:({signal})=>api.getRobots(page,signal),retry:false});
 async function submit(event:FormEvent){event.preventDefault();if(await action.run(async()=>{await api.createRobot(name.trim(),description.trim());await client.invalidateQueries({queryKey:["workspace"]});},"Robô cadastrado.")){setCreating(false);setName("");setDescription("");setPage(0);}}
 const visible=query.data?.filter(r=>(archived||!r.archived)&&r.name.toLocaleLowerCase("pt-BR").includes(search.toLocaleLowerCase("pt-BR")))??[];
 return <><Heading title="Robôs e componentes" description="Organize cada robô, categoria e peça antes da retirada."><button className="primary-button" onClick={()=>setCreating(!creating)}><Plus size={17}/>{creating?"Fechar cadastro":"Adicionar robô"}</button></Heading>
  <Feedback {...action}/>
  {creating&&<form className="panel editor-panel" onSubmit={event=>void submit(event)}><h2>Cadastrar robô</h2><fieldset disabled={action.busy}>
   <label>Nome do robô<input autoFocus required minLength={2} maxLength={100} value={name} onChange={e=>setName(e.target.value)}/></label>
   <label>Descrição<textarea maxLength={1000} value={description} onChange={e=>setDescription(e.target.value)}/></label>
   <div className="workspace-actions"><button className="primary-button">Salvar robô</button><button type="button" className="secondary-button" onClick={()=>setCreating(false)}>Cancelar</button></div>
  </fieldset></form>}
  <div className="workspace-toolbar"><label className="search-box"><Search size={18}/><input aria-label="Buscar nesta página de robôs" placeholder="Buscar nesta página…" value={search} onChange={e=>setSearch(e.target.value)}/></label><label className="check-label"><input type="checkbox" checked={archived} onChange={e=>setArchived(e.target.checked)}/> Mostrar arquivados</label><button className="secondary-button" disabled={query.isFetching} onClick={()=>void query.refetch()}>Atualizar</button></div>
  <QueryState pending={query.isPending} error={query.isError?query.error:null} retry={()=>void query.refetch()}/>
  {!query.isPending&&!query.isError&&(visible.length?<div className="robot-grid">{visible.map(r=><Link className="panel robot-card" to={"/robos/"+r.id} key={r.id}><span className="robot-card-icon"><Bot size={27}/></span><span className="state-pill">{r.archived?"Arquivado":"Ativo"}</span><h2>{r.name}</h2><p>{r.description||"Adicione categorias e componentes para preparar a retirada."}</p><span className="card-link">Abrir estrutura →</span></Link>)}</div>:<div className="panel empty-state"><Bot size={32}/><h2>{query.data?.length?"Nenhum robô corresponde ao filtro.":"Nenhum robô nesta página."}</h2><p>Use “Adicionar robô” para começar a organizar os componentes.</p></div>)}
  <Pagination page={page} setPage={setPage} hasNext={query.data?.length===20}/>
 </>;
}
