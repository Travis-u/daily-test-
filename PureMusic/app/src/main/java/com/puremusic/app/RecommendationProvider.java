package com.puremusic.app;

/** Replaceable recommendation boundary. No Android, credentials, audio or network here. */
public interface RecommendationProvider {
    RecommendationEngine.Reply respond(String message);
    void select(String songId);
    void reset();
}
