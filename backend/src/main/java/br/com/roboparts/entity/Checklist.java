package br.com.roboparts.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="checklists",schema="roboparts")
public class Checklist {
 @Id public UUID id;
 @Column(name="robot_id",nullable=false,updatable=false) public UUID robotId;
 @Column(name="request_id",nullable=false,updatable=false) public UUID requestId;
 @Column(name="robot_name",nullable=false,length=100,updatable=false) public String robotName;
 @Column(name="robot_description",nullable=false,length=1000,updatable=false) public String robotDescription;
 @Column(name="started_by",nullable=false,updatable=false) public UUID startedBy;
 @Column(nullable=false,length=20) public String status;
 @Version public long version;
 @Column(name="created_at",nullable=false,updatable=false) public Instant createdAt;
 @Column(name="updated_at",nullable=false) public Instant updatedAt;
 @Column(name="finalized_at") public Instant finalizedAt;
 @Column(name="finalized_by") public UUID finalizedBy;
 public Checklist() {}
}
