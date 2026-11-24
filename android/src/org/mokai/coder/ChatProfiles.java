package org.mokai.coder;

import org.json.JSONException;
import org.json.JSONObject;

/** The complete chat configuration is saved as one encrypted record. */
final class ChatProfiles {
    static final String STORAGE_NAME = "chat";
    static final int OPENROUTER = 0;
    static final int SERVER = 1;
    static final int GROK = 2;

    private final JSONObject profiles;
    private final int active;

    private ChatProfiles(JSONObject profiles, int active) {
        this.profiles = profiles;
        this.active = active;
    }

    static ChatProfiles from(JSONObject saved) throws JSONException {
        JSONObject stored = saved.optJSONObject("profiles");
        if (stored != null) return new ChatProfiles(stored, knownMode(saved.optInt("active", GROK)));

        JSONObject migrated = new JSONObject();
        if (!saved.optString("secret").isEmpty()) {
            int mode = knownMode(saved.optInt("mode", OPENROUTER));
            migrated.put(storageKey(mode), saved);
            return new ChatProfiles(migrated, mode);
        }
        return new ChatProfiles(migrated, GROK);
    }

    int activeMode() {
        return active;
    }

    JSONObject get(int mode) {
        JSONObject configuration = profiles.optJSONObject(storageKey(mode));
        return configuration == null ? new JSONObject() : configuration;
    }

    ChatProfiles select(int mode, JSONObject configuration) throws JSONException {
        JSONObject updated = new JSONObject(profiles.toString());
        updated.put(storageKey(mode), configuration);
        return new ChatProfiles(updated, mode);
    }

    ChatProfiles remove(int mode) throws JSONException {
        JSONObject updated = new JSONObject(profiles.toString());
        updated.remove(storageKey(mode));
        return new ChatProfiles(updated, active);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("active", active).put("profiles", profiles);
    }

    private static int knownMode(int mode) {
        return mode == OPENROUTER || mode == SERVER || mode == GROK ? mode : GROK;
    }

    private static String storageKey(int mode) {
        switch (mode) {
            case OPENROUTER: return "openrouter";
            case SERVER: return "server";
            case GROK: return "grok";
            default: throw new IllegalArgumentException("Unknown connection mode");
        }
    }
}
