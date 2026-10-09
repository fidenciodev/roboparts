package br.com.roboparts.controller;
import br.com.roboparts.dto.WorkspaceDtos.*;
import br.com.roboparts.security.SessionUser;
import br.com.roboparts.service.WorkspaceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api") @Tag(name="Robôs, retiradas e histórico")
public class WorkspaceController {
 private final WorkspaceService service;
 public WorkspaceController(WorkspaceService service) { this.service=service; }
 @GetMapping("/robots")
 public List<RobotView> robots(@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,
    @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) { return service.robots(page,limit); }
 @GetMapping("/robots/{id}") public RobotView robot(@PathVariable UUID id) { return service.robot(id); }
 @PostMapping("/robots") @ResponseStatus(HttpStatus.CREATED)
 public RobotView create(@Valid @RequestBody RobotInput body,@AuthenticationPrincipal SessionUser user) { return service.createRobot(body,user); }
 @PostMapping("/robots/{id}") public RobotView update(@PathVariable UUID id,@Valid @RequestBody RobotUpdate body,@AuthenticationPrincipal SessionUser user) { return service.updateRobot(id,body,user); }
 @PostMapping("/robots/{id}/nodes") @ResponseStatus(HttpStatus.CREATED)
 public RobotView addNode(@PathVariable UUID id,@Valid @RequestBody NodeInput body,@AuthenticationPrincipal SessionUser user) { return service.addNode(id,body,user); }
 @PostMapping("/robots/{id}/nodes/{nodeId}")
 public RobotView updateNode(@PathVariable UUID id,@PathVariable UUID nodeId,@Valid @RequestBody NodeUpdate body,@AuthenticationPrincipal SessionUser user) { return service.updateNode(id,nodeId,body,user); }
 @PostMapping("/robots/{id}/checklists") @ResponseStatus(HttpStatus.CREATED)
 public ChecklistView start(@PathVariable UUID id,@Valid @RequestBody StartInput body,@AuthenticationPrincipal SessionUser user) { return service.start(id,body,user); }
 @GetMapping("/checklists") public List<ChecklistView> checklists(@RequestParam(required=false) UUID robotId,
   @RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit) { return service.checklists(robotId,page,limit); }
 @GetMapping("/checklists/{id}") public ChecklistView checklist(@PathVariable UUID id) { return service.checklist(id); }
 @PostMapping("/checklists/{id}/items/{itemId}/movements")
 public ChecklistView move(@PathVariable UUID id,@PathVariable UUID itemId,@Valid @RequestBody MovementInput body,@AuthenticationPrincipal SessionUser user) { return service.move(id,itemId,body,user); }
 @PostMapping("/checklists/{id}/finalize")
 public ChecklistView finalizeChecklist(@PathVariable UUID id,@Valid @RequestBody VersionInput body,@AuthenticationPrincipal SessionUser user) { return service.finalizeChecklist(id,body,user); }
 @PostMapping("/checklists/{id}/cancel")
 public ChecklistView cancel(@PathVariable UUID id,@Valid @RequestBody VersionInput body,@AuthenticationPrincipal SessionUser user) { return service.cancel(id,body,user); }
 @GetMapping("/dashboard") public DashboardView dashboard() { return service.dashboard(); }
 @GetMapping("/history") public List<HistoryView> history(@RequestParam(defaultValue="false") boolean mine,
   @RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit,
   @AuthenticationPrincipal SessionUser user) { return service.history(mine?user.id():null,page,limit); }
}
