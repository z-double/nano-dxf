package com.nanodxf;

import com.nanodxf.entity.CADEntity;
import com.nanodxf.model.DrawingMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ParseResult {
    private final List<CADEntity> entities;
    private final List<ParseError> errors;
    private final DrawingMetadata metadata;
    private final ParseStats stats;

    private ParseResult(Builder builder) {
        this.entities = Collections.unmodifiableList(new ArrayList<>(builder.entities));
        this.errors   = Collections.unmodifiableList(new ArrayList<>(builder.errors));
        this.metadata = builder.metadata;
        this.stats    = builder.stats;
    }

    public List<CADEntity> getEntities() { return entities; }
    public List<ParseError> getErrors() { return errors; }
    public DrawingMetadata getMetadata() { return metadata; }
    public ParseStats getStats() { return stats; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<CADEntity> entities = new ArrayList<>();
        private final List<ParseError> errors  = new ArrayList<>();
        private DrawingMetadata metadata;
        private ParseStats stats;

        public Builder addEntity(CADEntity e) { entities.add(e); return this; }
        public Builder addError(ParseError e) { errors.add(e); return this; }
        public Builder metadata(DrawingMetadata m) { this.metadata = m; return this; }
        public Builder stats(ParseStats s) { this.stats = s; return this; }
        public ParseResult build() { return new ParseResult(this); }
    }
}
