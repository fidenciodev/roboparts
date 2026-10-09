package br.com.roboparts.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="checklist_items",schema="roboparts")
public class ChecklistItem {
 @Id public UUID id;
 @Column(name="checklist_id",nullable=false,updatable=false) public UUID checklistId;
 @Column(name="source_node_id",nullable=false,updatable=false) public UUID sourceNodeId;
 @Column(name="parent_id",updatable=false) public UUID parentId;
 @Column(nullable=false,length=20,updatable=false) public String kind;
 @Column(nullable=false,length=100,updatable=false) public String name;
 @Column(nullable=false,length=1000,updatable=false) public String description;
 @Column(nullable=false,updatable=false) public int quantity;
 @Column(nullable=false,updatable=false) public boolean required;
 @Column(nullable=false,updatable=false) public int position;
 @Column(name="taken_quantity",nullable=false) public int takenQuantity;
 @Column(name="checked_by") public UUID checkedBy;
 @Column(name="checked_at") public Instant checkedAt;
 public ChecklistItem() {}
}
