package com.messagebox.model;

import java.util.*;

public class Room {
    private String id;
    private String name;
    private RoomType type;
    private String creator;
    private long createdAt;
    private Set<String> members;

    public Room() {
        this.members = new HashSet<>();
    }

    public Room(String id, String name, RoomType type, String creator) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.creator = creator;
        this.createdAt = System.currentTimeMillis();
        this.members = new HashSet<>();
    }

    public String getId()        { return id; }
    public String getName()      { return name; }
    public RoomType getType()    { return type; }
    public String getCreator()   { return creator; }
    public long getCreatedAt()   { return createdAt; }
    public Set<String> getMembers() { return members; }

    public void addMember(String user)    { members.add(user.toLowerCase()); }
    public void removeMember(String user) { members.remove(user.toLowerCase()); }
    public boolean hasMember(String user)  { return members.contains(user.toLowerCase()); }
}