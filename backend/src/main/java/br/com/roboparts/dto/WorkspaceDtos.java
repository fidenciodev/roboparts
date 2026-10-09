package br.com.roboparts.dto;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
public final class WorkspaceDtos {
 private WorkspaceDtos() {}
 public record RobotInput(@NotBlank @Size(min=2,max=100) String name,@NotNull @Size(max=1000) String description) {}
 public record RobotUpdate(@NotBlank @Size(min=2,max=100) String name,@NotNull @Size(max=1000) String description,
   boolean archived,@NotNull @PositiveOrZero Long expectedVersion) {}
 public record NodeInput(@NotBlank @Pattern(regexp="COMPONENT") String kind,
   @NotBlank @Size(min=2,max=100) String name,@NotNull @Size(max=1000) String description, @Null UUID parentId,
   @Min(1) @Max(1000000) int quantity,boolean required,@NotNull @PositiveOrZero Long expectedVersion) {}
 public record NodeUpdate(@NotBlank @Size(min=2,max=100) String name,@NotNull @Size(max=1000) String description,@Null UUID parentId,
   @Min(1) @Max(1000000) int quantity,boolean required,@Min(0) @Max(10000) int position,
   boolean archived,@NotNull @PositiveOrZero Long expectedVersion) {}
 public record VersionInput(@NotNull @PositiveOrZero Long expectedVersion) {}
 public record StartInput(@NotNull UUID requestId,@NotNull @PositiveOrZero Long expectedVersion) {}
 public record MovementInput(@Min(0) @Max(1000000) int quantity,@NotNull UUID requestId,@NotNull @PositiveOrZero Long expectedVersion) {}
 public record RobotView(UUID id,String name,String description,boolean archived,long version,Instant createdAt,List<NodeView> nodes) {}
 public record NodeView(UUID id,UUID parentId,String kind,String name,String description,int quantity,boolean required,int position,boolean archived) {}
 public record ChecklistView(UUID id,UUID robotId,String robotName,String robotDescription,String status,long version,
   UUID startedBy,String startedByName,Instant createdAt,Instant finalizedAt,String finalizedByName,
   int total,int completed,int pending,List<ItemView> items,List<MovementView> movements) {}
 public record ItemView(UUID id,UUID parentId,String kind,String name,String description,int quantity,boolean required,
   int position,int takenQuantity,String checkedByName,Instant checkedAt) {}
 public record MovementView(UUID id,UUID itemId,String itemName,int delta,int resultingQuantity,String userName,Instant createdAt) {}
 public record HistoryView(UUID id,UUID userId,String userName,String action,String details,String resourceType,UUID resourceId,Instant createdAt) {}
 public record DashboardView(long robots,long robotsInUse,long inProgress,long completed,long pendingComponents,List<HistoryView> recent) {}
}
