package com.nanodxf;

public class ParseError {
    private final ParseErrorLevel level;
    private final String entityType;
    private final String handle;
    private final String message;

    public ParseError(ParseErrorLevel level, String entityType, String handle, String message) {
        this.level = level;
        this.entityType = entityType;
        this.handle = handle;
        this.message = message;
    }

    public ParseErrorLevel getLevel() { return level; }
    public String getEntityType() { return entityType; }
    public String getHandle() { return handle; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return "[" + level + "] " + entityType + " (handle=" + handle + "): " + message;
    }
}
