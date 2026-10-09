package br.com.roboparts.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="robots",schema="roboparts")
public class Robot {
 @Id public UUID id;
 @Column(nullable=false,length=100) public String name;
 @Column(nullable=false,length=1000) public String description;
 @Column(nullable=false) public boolean archived;
 @Version public long version;
 @Column(name="created_at",nullable=false,updatable=false) public Instant createdAt;
 @Column(name="updated_at",nullable=false) public Instant updatedAt;
 public Robot() {}
 public Robot(String name,String description) { id=UUID.randomUUID(); this.name=name; this.description=description; createdAt=updatedAt=Instant.now(); }
}
