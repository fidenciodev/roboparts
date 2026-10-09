package br.com.roboparts.service;

import br.com.roboparts.dto.WorkspaceDtos.*;
import br.com.roboparts.entity.*;
import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.repository.*;
import br.com.roboparts.security.SessionUser;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkspaceService {
 private final RobotRepository robots;
 private final ComponentNodeRepository nodes;
 private final ChecklistRepository checklists;
 private final ChecklistItemRepository items;
 private final MovementRepository movements;
 private final AuditEventRepository audit;
 private final JdbcTemplate jdbc;
 public WorkspaceService(RobotRepository robots,ComponentNodeRepository nodes,ChecklistRepository checklists,
   ChecklistItemRepository items,MovementRepository movements,AuditEventRepository audit,JdbcTemplate jdbc) {
  this.robots=robots;this.nodes=nodes;this.checklists=checklists;this.items=items;this.movements=movements;this.audit=audit;this.jdbc=jdbc;
 }
 private static ApiRequestException fail(HttpStatus status,String code,String message) { return new ApiRequestException(status,code,message); }
 private static ApiRequestException missing() { return fail(HttpStatus.NOT_FOUND,"not_found","O registro não foi encontrado."); }
 private static void version(long actual,Long expected) {
  if(expected==null || actual!=expected) throw fail(HttpStatus.CONFLICT,"concurrent_update","Outra operação alterou estes dados. Atualize antes de salvar.");
 }
 private static String name(String value) {
  if(value==null || value.trim().length()<2 || value.trim().length()>100) throw fail(HttpStatus.BAD_REQUEST,"validation_error","O nome deve ter entre 2 e 100 caracteres.");
  return value.trim();
 }
 private static void active(Robot r) { if(r.archived) throw fail(HttpStatus.CONFLICT,"robot_archived","Restaure o robô antes de alterar sua estrutura ou iniciar uma retirada."); }
 private Robot lockedRobot(UUID id) { return robots.lockById(id).orElseThrow(WorkspaceService::missing); }
 private void event(SessionUser u,AuditAction action,String detail,String type,UUID id) {
  audit.save(new AuditEvent(u.id(),action,detail,type,id));
 }
 private RobotView robotView(Robot r,boolean tree) {
  return new RobotView(r.id,r.name,r.description,r.archived,r.version,r.createdAt,
    tree?nodes.findByRobotIdOrderByPositionAscIdAsc(r.id).stream().map(n->new NodeView(n.id,n.parentId,n.kind,n.name,n.description,n.quantity,n.required,n.position,n.archived)).toList():List.of());
 }
 @Transactional(readOnly=true)
 public List<RobotView> robots(int page,int limit) { return robots.findAllByOrderByNameAscIdAsc(PageRequest.of(page,limit)).stream().map(r->robotView(r,false)).toList(); }
 @Transactional(readOnly=true)
 public RobotView robot(UUID id) { return robotView(robots.findById(id).orElseThrow(WorkspaceService::missing),true); }
 public RobotView createRobot(RobotInput body,SessionUser u) {
  Robot r=robots.saveAndFlush(new Robot(name(body.name()),body.description().trim()));
  event(u,AuditAction.ROBOT_CREATED,"Cadastrou o robô "+r.name,"ROBOT",r.id);
  return robotView(r,true);
 }
 public RobotView updateRobot(UUID id,RobotUpdate body,SessionUser u) {
  Robot r=lockedRobot(id);version(r.version,body.expectedVersion());
  String previous=r.name;String previousDescription=r.description;boolean previousArchived=r.archived;
  r.name=name(body.name());r.description=body.description().trim();r.archived=body.archived();r.updatedAt=Instant.now();
  robots.flush();
  event(u,AuditAction.ROBOT_UPDATED,"Alterou "+previous+" → "+r.name+
    "; "+(previousArchived?"arquivado":"ativo")+" → "+(r.archived?"arquivado":"ativo")+
    (!previousDescription.equals(r.description)?"; descrição alterada":""),"ROBOT",id);
  return robotView(r,true);
 }
 private void parent(List<ComponentNode> all,UUID parentId,UUID movingId) {
  if(parentId==null) return;
  Map<UUID,ComponentNode> byId=all.stream().collect(Collectors.toMap(n->n.id,Function.identity()));
  ComponentNode p=byId.get(parentId);
  if(p==null || !"CATEGORY".equals(p.kind) || p.archived) throw fail(HttpStatus.BAD_REQUEST,"invalid_parent","Escolha uma categoria ativa do mesmo robô.");
  Set<UUID> visited=new HashSet<>();
  UUID cursor=parentId;
  while(cursor!=null) {
   if(Objects.equals(cursor,movingId) || !visited.add(cursor)) throw fail(HttpStatus.BAD_REQUEST,"invalid_hierarchy","Uma categoria não pode conter a si mesma ou um ancestral.");
   if(visited.size()>32) throw fail(HttpStatus.BAD_REQUEST,"invalid_hierarchy","A estrutura permite até 32 níveis.");
   ComponentNode ancestor=byId.get(cursor); if(ancestor==null) throw missing(); cursor=ancestor.parentId;
  }
 }
 public RobotView addNode(UUID robotId,NodeInput body,SessionUser u) {
  Robot r=lockedRobot(robotId);version(r.version,body.expectedVersion());active(r);
  List<ComponentNode> all=nodes.findByRobotIdOrderByPositionAscIdAsc(robotId);
  if(all.size()>=1000) throw fail(HttpStatus.CONFLICT,"structure_limit","Este robô atingiu o limite de 1000 elementos.");
  parent(all,body.parentId(),null);
  ComponentNode n=new ComponentNode();n.id=UUID.randomUUID();n.robotId=robotId;n.parentId=body.parentId();
  n.kind=body.kind();n.name=name(body.name());n.description=body.description().trim();n.quantity=body.quantity();n.required=body.required();
  n.position=(int)all.stream().filter(x->Objects.equals(x.parentId,n.parentId)).count();
  nodes.saveAndFlush(n);r.updatedAt=Instant.now();robots.flush();
  event(u,AuditAction.NODE_CREATED,"Adicionou "+n.name+" ao robô "+r.name,"ROBOT",r.id);
  return robotView(r,true);
 }
 public RobotView updateNode(UUID robotId,UUID nodeId,NodeUpdate body,SessionUser u) {
  Robot r=lockedRobot(robotId);version(r.version,body.expectedVersion());active(r);
  List<ComponentNode> all=nodes.findByRobotIdOrderByPositionAscIdAsc(robotId);
  ComponentNode n=all.stream().filter(x->x.id.equals(nodeId)).findFirst().orElseThrow(WorkspaceService::missing);
  parent(all,body.parentId(),nodeId);
  // Include descendants when calculating the final depth, not just the destination.
  Map<UUID,UUID> parents=all.stream().filter(x->x.parentId!=null).collect(Collectors.toMap(x->x.id,x->x.parentId));
  if(body.parentId()==null) parents.remove(nodeId);else parents.put(nodeId,body.parentId());
  for(ComponentNode x:all) {
   Set<UUID> seen=new HashSet<>();UUID cursor=x.id;
   while(cursor!=null) { if(!seen.add(cursor)||seen.size()>33) throw fail(HttpStatus.BAD_REQUEST,"invalid_hierarchy","A hierarquia é inválida ou excede 32 níveis.");cursor=parents.get(cursor); }
  }
  String previous=n.name;String previousDescription=n.description;UUID oldParent=n.parentId;
  int oldQuantity=n.quantity;int oldPosition=n.position;boolean oldRequired=n.required;boolean oldArchived=n.archived;
  n.name=name(body.name());n.description=body.description().trim();n.quantity=body.quantity();n.required=body.required();n.parentId=body.parentId();n.archived=body.archived();
  if(n.archived) {
   Set<UUID> subtree=new HashSet<>(Set.of(n.id));boolean changed;
   do { changed=false;for(ComponentNode x:all) if(x.parentId!=null&&subtree.contains(x.parentId)) changed|=subtree.add(x.id); } while(changed);
   all.stream().filter(x->subtree.contains(x.id)).forEach(x->x.archived=true);
  }
  List<ComponentNode> siblings=new ArrayList<>(all.stream().filter(x->!x.id.equals(n.id)&&Objects.equals(x.parentId,n.parentId)).toList());
  siblings.add(Math.min(body.position(),siblings.size()),n);
  for(int i=0;i<siblings.size();i++) siblings.get(i).position=i;
  if(!Objects.equals(oldParent,n.parentId)) {
   List<ComponentNode> old=all.stream().filter(x->Objects.equals(x.parentId,oldParent)).toList();
   for(int i=0;i<old.size();i++)old.get(i).position=i;
  }
  nodes.flush();r.updatedAt=Instant.now();robots.flush();
  String previousParent=oldParent==null?"raiz":all.stream().filter(x->x.id.equals(oldParent)).findFirst().map(x->x.name).orElse("");
  String currentParent=n.parentId==null?"raiz":all.stream().filter(x->x.id.equals(n.parentId)).findFirst().map(x->x.name).orElse("");
  event(u,AuditAction.NODE_UPDATED,"Alterou "+previous+" → "+n.name+"; quantidade "+oldQuantity+" → "+n.quantity+
    "; categoria "+previousParent+" → "+currentParent+"; posição "+(oldPosition+1)+" → "+(n.position+1)+
    "; obrigatório "+oldRequired+" → "+n.required+"; "+(oldArchived?"arquivado":"ativo")+" → "+(n.archived?"arquivado":"ativo")+
    (!previousDescription.equals(n.description)?"; descrição alterada":""),"ROBOT",r.id);
  return robotView(r,true);
 }
 public ChecklistView start(UUID robotId,StartInput body,SessionUser u) {
  Robot r=lockedRobot(robotId);
  Optional<Checklist> repeated=checklists.findByRobotIdAndRequestId(robotId,body.requestId());
  if(repeated.isPresent()) {
   if(!repeated.get().startedBy.equals(u.id())) throw fail(HttpStatus.CONFLICT,"duplicate_operation","Este identificador já foi utilizado.");
   return checklistView(repeated.get(),true);
  }
  version(r.version,body.expectedVersion());active(r);
  List<ComponentNode> source=nodes.findByRobotIdOrderByPositionAscIdAsc(robotId).stream().filter(n->!n.archived).toList();
  if(source.stream().noneMatch(n->n.kind.equals("COMPONENT"))) throw fail(HttpStatus.CONFLICT,"empty_structure","Adicione pelo menos um componente ativo antes de iniciar um checklist.");
  Checklist c=new Checklist();c.id=UUID.randomUUID();c.requestId=body.requestId();c.robotId=r.id;c.robotName=r.name;c.robotDescription=r.description;
  c.startedBy=u.id();c.status="PENDING";c.createdAt=c.updatedAt=Instant.now();checklists.saveAndFlush(c);
  Map<UUID,UUID> ids=new HashMap<>();source.forEach(n->ids.put(n.id,UUID.randomUUID()));
  // Persist parents first to satisfy the composite FK regardless of sibling ordering.
  Set<UUID> saved=new HashSet<>();
  while(saved.size()<source.size()) {
   boolean progressed=false;
   for(ComponentNode n:source) if(!saved.contains(n.id)&&(n.parentId==null||saved.contains(n.parentId))) {
    ChecklistItem item=new ChecklistItem();item.id=ids.get(n.id);item.checklistId=c.id;item.sourceNodeId=n.id;item.parentId=ids.get(n.parentId);
    item.kind=n.kind;item.name=n.name;item.description=n.description;item.quantity=n.quantity;item.required=n.required;item.position=n.position;
    items.saveAndFlush(item);saved.add(n.id);progressed=true;
   }
   if(!progressed) throw fail(HttpStatus.CONFLICT,"invalid_hierarchy","A estrutura não pôde ser copiada.");
  }
  event(u,AuditAction.CHECKLIST_STARTED,"Iniciou uma retirada de "+r.name,"CHECKLIST",c.id);
  return checklistView(c,true);
 }
 private String userName(UUID id,Map<UUID,String> cache) {
  if(id==null)return null;
  return cache.computeIfAbsent(id,key->jdbc.queryForObject("SELECT name FROM roboparts.users WHERE id = ?",String.class,key));
 }
 private ChecklistView checklistView(Checklist c,boolean full) {
  List<ChecklistItem> list=items.findByChecklistIdOrderByPositionAscIdAsc(c.id);
  List<ChecklistItem> components=list.stream().filter(i->i.kind.equals("COMPONENT")&&i.required).toList();
  int done=(int)components.stream().filter(i->i.takenQuantity==i.quantity).count();
  Map<UUID,String> names=new HashMap<>();
  Map<UUID,String> itemNames=list.stream().collect(Collectors.toMap(i->i.id,i->i.name));
  return new ChecklistView(c.id,c.robotId,c.robotName,c.robotDescription,c.status,c.version,c.startedBy,userName(c.startedBy,names),c.createdAt,c.finalizedAt,userName(c.finalizedBy,names),
    components.size(),done,components.size()-done,
    full?list.stream().map(i->new ItemView(i.id,i.parentId,i.kind,i.name,i.description,i.quantity,i.required,i.position,i.takenQuantity,userName(i.checkedBy,names),i.checkedAt)).toList():List.of(),
    full?movements.findByChecklistIdOrderByCreatedAtAscIdAsc(c.id).stream().map(m->new MovementView(m.id,m.itemId,itemNames.get(m.itemId),m.delta,m.resultingQuantity,userName(m.userId,names),m.createdAt)).toList():List.of());
 }
 @Transactional(readOnly=true)
 public ChecklistView checklist(UUID id) { return checklistView(checklists.findById(id).orElseThrow(WorkspaceService::missing),true); }
 @Transactional(readOnly=true)
 public List<ChecklistView> checklists(UUID robotId,int page,int limit) {
  return (robotId==null?checklists.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page,limit)):
    checklists.findByRobotIdOrderByCreatedAtDescIdDesc(robotId,PageRequest.of(page,limit))).stream().map(c->checklistView(c,false)).toList();
 }
 public ChecklistView move(UUID checklistId,UUID itemId,MovementInput body,SessionUser u) {
  Checklist c=checklists.lockById(checklistId).orElseThrow(WorkspaceService::missing);
  Optional<Movement> previous=movements.findByChecklistIdAndRequestId(checklistId,body.requestId());
  if(previous.isPresent()) {
   Movement m=previous.get();
   if(!m.itemId.equals(itemId)||!m.userId.equals(u.id())||m.resultingQuantity!=body.quantity())
    throw fail(HttpStatus.CONFLICT,"duplicate_operation","O identificador já foi usado para outra operação.");
   return checklistView(c,true);
  }
  version(c.version,body.expectedVersion());
  if(c.status.equals("CANCELLED")||c.status.equals("RETURNED")) throw fail(HttpStatus.CONFLICT,"checklist_closed","Este checklist está encerrado. Inicie uma nova retirada.");
  List<ChecklistItem> all=items.findByChecklistIdOrderByPositionAscIdAsc(checklistId);
  ChecklistItem item=all.stream().filter(i->i.id.equals(itemId)&&i.kind.equals("COMPONENT")).findFirst().orElseThrow(WorkspaceService::missing);
  if(body.quantity()<0||body.quantity()>item.quantity) throw fail(HttpStatus.BAD_REQUEST,"invalid_quantity","A quantidade deve estar entre zero e a quantidade necessária.");
  if(c.finalizedAt!=null&&body.quantity()>item.takenQuantity) throw fail(HttpStatus.CONFLICT,"checklist_closed","Após finalizar, apenas devoluções são permitidas.");
  int delta=body.quantity()-item.takenQuantity;
  if(delta==0)return checklistView(c,true);
  item.takenQuantity=body.quantity();item.checkedBy=u.id();item.checkedAt=Instant.now();items.flush();
  if(c.finalizedAt!=null)c.status=all.stream().allMatch(i->i.takenQuantity==0)?"RETURNED":"RETURNING";
  else c.status=all.stream().anyMatch(i->i.takenQuantity>0)?"IN_PROGRESS":"PENDING";
  // Force a version change even when the lifecycle status stays the same.
  c.updatedAt=Instant.now();checklists.flush();
  Movement m=new Movement();m.id=UUID.randomUUID();m.checklistId=c.id;m.itemId=item.id;m.requestId=body.requestId();m.userId=u.id();m.delta=delta;
  m.resultingQuantity=item.takenQuantity;m.createdAt=item.checkedAt;movements.save(m);
  event(u,delta>0?AuditAction.COMPONENT_WITHDRAWN:AuditAction.COMPONENT_RETURNED,
    (delta>0?"Retirou ":"Devolveu ")+Math.abs(delta)+" × "+item.name+" de "+c.robotName,"CHECKLIST",c.id);
  return checklistView(c,true);
 }
 public ChecklistView finalizeChecklist(UUID id,VersionInput body,SessionUser u) {
  Checklist c=checklists.lockById(id).orElseThrow(WorkspaceService::missing);version(c.version,body.expectedVersion());
  if(c.finalizedAt!=null||c.status.equals("CANCELLED")) throw fail(HttpStatus.CONFLICT,"checklist_closed","O checklist já foi encerrado.");
  List<ChecklistItem> all=items.findByChecklistIdOrderByPositionAscIdAsc(id);
  if(all.stream().anyMatch(i->i.kind.equals("COMPONENT")&&i.required&&i.takenQuantity!=i.quantity)||all.stream().noneMatch(i->i.takenQuantity>0))
   throw fail(HttpStatus.CONFLICT,"pending_components","Confira todos os componentes obrigatórios e registre ao menos uma retirada.");
  c.status="COMPLETED";c.finalizedAt=Instant.now();c.finalizedBy=u.id();checklists.flush();
  event(u,AuditAction.CHECKLIST_FINALIZED,"Finalizou a retirada de "+c.robotName,"CHECKLIST",id);
  return checklistView(c,true);
 }
 public ChecklistView cancel(UUID id,VersionInput body,SessionUser u) {
  Checklist c=checklists.lockById(id).orElseThrow(WorkspaceService::missing);version(c.version,body.expectedVersion());
  if(c.finalizedAt!=null||c.status.equals("CANCELLED")||items.findByChecklistIdOrderByPositionAscIdAsc(id).stream().anyMatch(i->i.takenQuantity>0))
   throw fail(HttpStatus.CONFLICT,"checklist_closed","Para cancelar, devolva os componentes de um checklist ainda não finalizado.");
  c.status="CANCELLED";checklists.flush();event(u,AuditAction.CHECKLIST_CANCELLED,"Cancelou a retirada de "+c.robotName,"CHECKLIST",id);
  return checklistView(c,true);
 }
 @Transactional(readOnly=true)
 public List<HistoryView> history(UUID userId,int page,int limit) {
  String filter=userId==null?"":" WHERE a.user_id = ?";
  List<Object> args=new ArrayList<>();if(userId!=null)args.add(userId);args.add(limit);args.add(page*limit);
  return jdbc.query("SELECT a.*,u.name AS user_name FROM roboparts.audit_events a JOIN roboparts.users u ON u.id=a.user_id"+
    filter+" ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",(rs,row)->new HistoryView(rs.getObject("id",UUID.class),
    rs.getObject("user_id",UUID.class),rs.getString("user_name"),rs.getString("action"),rs.getString("details"),
    rs.getString("resource_type"),rs.getObject("resource_id",UUID.class),rs.getTimestamp("created_at").toInstant()),args.toArray());
 }
 private long count(String sql) { Long value=jdbc.queryForObject(sql,Long.class);return value==null?0:value; }
 @Transactional(readOnly=true)
 public DashboardView dashboard() {
  return new DashboardView(count("SELECT COUNT(*) FROM roboparts.robots WHERE archived=FALSE"),
    count("SELECT COUNT(DISTINCT c.robot_id) FROM roboparts.checklists c JOIN roboparts.checklist_items i ON i.checklist_id=c.id WHERE i.taken_quantity>0"),
    count("SELECT COUNT(*) FROM roboparts.checklists WHERE status IN ('PENDING','IN_PROGRESS')"),
    count("SELECT COUNT(*) FROM roboparts.checklists WHERE finalized_at IS NOT NULL"),
    count("SELECT COUNT(*) FROM roboparts.checklist_items i JOIN roboparts.checklists c ON c.id=i.checklist_id WHERE c.status IN ('PENDING','IN_PROGRESS') AND i.kind='COMPONENT' AND i.required=TRUE AND i.taken_quantity<i.quantity"),
    history(null,0,10));
 }
}
