package br.com.roboparts.entity;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="component_nodes",schema="roboparts")
public class ComponentNode {
 @Id public UUID id;
 @Column(name="robot_id",nullable=false,updatable=false) public UUID robotId;
 @Column(name="parent_id") public UUID parentId;
 @Column(nullable=false,length=20,updatable=false) public String kind;
 @Column(nullable=false,length=100) public String name;
 @Column(nullable=false,length=1000) public String description;
 @Column(nullable=false) public int quantity;
 @Column(nullable=false) public boolean required;
 @Column(nullable=false) public int position;
 @Column(nullable=false) public boolean archived;
 public ComponentNode() {}
}
