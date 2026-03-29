package com.messagebox;

import com.google.gson.Gson;

public class Message {

    private static final Gson gson = new Gson();

    private MessageType type;
    private String sender;
    private String content;
    private long timestamp;
    private String recipient;

    public Message() {}

    public Message(MessageType type, String sender, String content) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String sender, String content, String recipient) {
        this(type, sender, content);
        this.recipient = recipient;
    }

    // Геттеры
    public MessageType getType()  { return type; }
    public String getSender()     { return sender; }
    public String getContent()    { return content; }
    public long getTimestamp()     { return timestamp; }
    public String getRecipient()  { return recipient; }

    // Сеттеры
    public void setSender(String sender)       { this.sender = sender; }
    public void setTimestamp(long timestamp)    { this.timestamp = timestamp; }

    // JSON
    public String toJson() {
        return gson.toJson(this);
    }

    public static Message fromJson(String json) {
        return gson.fromJson(json, Message.class);
    }
}