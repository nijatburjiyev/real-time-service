package com.companya.realtime.kafka;

public enum EventType {
    TEAMCREATE(ObjectType.TEAM, EventAction.CREATE),
    TEAMUPDATE(ObjectType.TEAM, EventAction.UPDATE),
    TEAMEND(ObjectType.TEAM, EventAction.END),
    MEMBERCREATE(ObjectType.MEMBER, EventAction.CREATE),
    MEMBERUPDATE(ObjectType.MEMBER, EventAction.UPDATE),
    MEMBEREND(ObjectType.MEMBER, EventAction.END);

    private final ObjectType objectType;
    private final EventAction action;

    EventType(ObjectType objectType, EventAction action) {
        this.objectType = objectType;
        this.action = action;
    }

    public ObjectType getObjectType() {
        return objectType;
    }

    public EventAction getAction() {
        return action;
    }

    public static EventType from(ObjectType objectType, EventAction action) {
        if (objectType == null || action == null) {
            return null;
        }
        for (EventType type : values()) {
            if (type.objectType == objectType && type.action == action) {
                return type;
            }
        }
        return null;
    }
}
