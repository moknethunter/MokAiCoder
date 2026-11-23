package org.mokai.coder;

import org.json.JSONObject;

public final class ChatProfilesTest {
    public static void main(String[] args) throws Exception {
        JSONObject legacy = new JSONObject().put("mode", ChatProfiles.GROK)
                .put("secret", "test-grok").put("model", "grok-test");
        ChatProfiles profiles = ChatProfiles.from(legacy);
        check(profiles.activeMode() == ChatProfiles.GROK, "Retain the legacy active provider");
        check(profiles.get(ChatProfiles.GROK).getString("secret").equals("test-grok"),
                "Keep the legacy key");

        profiles = reopen(profiles.select(ChatProfiles.OPENROUTER,
                new JSONObject().put("secret", "test-openrouter")));
        check(profiles.activeMode() == ChatProfiles.OPENROUTER, "Restore active profile after app restart");
        check(profiles.get(ChatProfiles.GROK).getString("model").equals("grok-test"),
                "Switching to OpenRouter preserves Grok");
        check(profiles.get(ChatProfiles.OPENROUTER).getString("secret").equals("test-openrouter"),
                "OpenRouter secret is local and independent");

        profiles = reopen(profiles.select(ChatProfiles.GROK, profiles.get(ChatProfiles.GROK)));
        check(profiles.activeMode() == ChatProfiles.GROK, "Switch back using the stored secret");
        check(profiles.get(ChatProfiles.OPENROUTER).getString("secret").equals("test-openrouter"),
                "Switching back preserves OpenRouter");

        JSONObject server = new JSONObject().put("secret", "test-server")
                .put("address", "https://example.invalid/");
        profiles = reopen(profiles.select(ChatProfiles.SERVER, server));
        check(profiles.get(ChatProfiles.SERVER).getString("address").equals("https://example.invalid/"),
                "Retain server configuration separately");
        profiles = reopen(profiles.remove(ChatProfiles.GROK));
        check(profiles.get(ChatProfiles.GROK).optString("secret").isEmpty(), "Remove only the selected key");
        check(profiles.get(ChatProfiles.OPENROUTER).getString("secret").equals("test-openrouter"),
                "Removing Grok preserves OpenRouter");
        check(profiles.activeMode() == ChatProfiles.SERVER, "Removing an inactive key preserves active mode");

        ChatProfiles removedActive = reopen(profiles.remove(ChatProfiles.SERVER));
        check(removedActive.activeMode() == ChatProfiles.SERVER
                && removedActive.get(ChatProfiles.SERVER).optString("secret").isEmpty(),
                "Never implicitly switch providers after deleting the active key");
        check(ChatProfiles.from(new JSONObject()).activeMode() == ChatProfiles.GROK, "New installation default");
        System.out.println("Chat profiles: migration, independent keys, restart and scoped removal passed.");
    }

    private static ChatProfiles reopen(ChatProfiles profiles) throws Exception {
        return ChatProfiles.from(new JSONObject(profiles.toJson().toString()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
