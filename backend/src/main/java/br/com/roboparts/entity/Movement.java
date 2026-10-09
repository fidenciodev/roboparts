package br.com.roboparts.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
@Entity @Immutable @Table(name="movements",schema="roboparts")
public class Movement {
 @Id public UUID id;
 @Column(name="checklist_id",nullable=false,updatable=false) public UUID checklistId;
 @Column(name="item_id",nullable=false,updatable=false) public UUID itemId;
 @Column(name="request_id",nullable=false,updatable=false) public UUID requestId;
 @Column(name="user_id",nullable=false,updatable=false) public UUID userId;
 @Column(nullable=false,updatable=false) public int delta;
 @Column(name="resulting_quantity",nullable=false,updatable=false) public int resultingQuantity;
 @Column(name="created_at",nullable=false,updatable=false) public Instant createdAt;
 public Movement() {}
}
