package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookRegistryTest {

    private static final Path DUMMY_DIR = Path.of("/hooks/example");

    @Test
    void parseDefinition_fullHookJson() {
        String json = """
            {
              "version": 1,
              "id": "git-push-pr-tip",
              "name": "PR creation tip",
              "description": "Appends a PR creation tip after pushing feature branches",
              "tools": ["git_push", "git_commit"],
              "hooks": {
                "post": {
                  "bash": "git-push-tip.sh",
                  "powershell": "git-push-tip.ps1",
                  "timeoutSec": 15,
                  "failOpen": true,
                  "env": {"CUSTOM_VAR": "value"}
                }
              }
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        HookDefinition def = HookRegistry.parseDefinition(root, DUMMY_DIR);

        assertEquals(1, def.version());
        assertEquals("git-push-pr-tip", def.id());
        assertEquals("PR creation tip", def.name());
        assertEquals("Appends a PR creation tip after pushing feature branches", def.description());
        assertEquals(2, def.tools().size());
        assertTrue(def.appliesTo("git_push"));
        assertTrue(def.appliesTo("git_commit"));
        assertFalse(def.appliesTo("read_file"));

        HookTriggerConfig postConfig = def.triggerConfig(HookTrigger.POST);
        assertNotNull(postConfig);
        assertEquals("git-push-tip.sh", postConfig.bash());
        assertEquals("git-push-tip.ps1", postConfig.powershell());
        assertEquals(15, postConfig.timeoutSec());
        assertTrue(postConfig.failOpen());
        assertEquals("value", postConfig.env().get("CUSTOM_VAR"));
    }

    @Test
    void parseDefinition_multipleTriggersIncludingPermission() {
        String json = """
            {
              "version": 1,
              "id": "security-scan",
              "name": "Security scan",
              "tools": ["run_command"],
              "hooks": {
                "permission": {
                  "bash": "check-allowed.sh",
                  "timeoutSec": 5,
                  "failOpen": false
                },
                "post": {
                  "bash": "redact-secrets.sh",
                  "failOpen": true
                }
              }
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        HookDefinition def = HookRegistry.parseDefinition(root, DUMMY_DIR);

        assertEquals("security-scan", def.id());
        assertNotNull(def.triggerConfig(HookTrigger.PERMISSION));
        assertNotNull(def.triggerConfig(HookTrigger.POST));
        assertNull(def.triggerConfig(HookTrigger.PRE));
        assertNull(def.triggerConfig(HookTrigger.ON_FAILURE));

        HookTriggerConfig permConfig = def.triggerConfig(HookTrigger.PERMISSION);
        assertNotNull(permConfig);
        assertEquals("check-allowed.sh", permConfig.bash());
        assertEquals(5, permConfig.timeoutSec());
        assertFalse(permConfig.failOpen());
    }

    @Test
    void parseDefinition_defaultsAppliedForMissingFields() {
        String json = """
            {
              "version": 1,
              "id": "minimal",
              "name": "Minimal hook",
              "tools": ["read_file"],
              "hooks": {
                "post": {
                  "bash": "my-hook.sh"
                }
              }
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        HookDefinition def = HookRegistry.parseDefinition(root, DUMMY_DIR);

        HookTriggerConfig config = def.triggerConfig(HookTrigger.POST);
        assertNotNull(config);
        assertEquals(10, config.timeoutSec());
        assertTrue(config.failOpen());
        assertTrue(config.env().isEmpty());
        assertNull(config.powershell());
        assertNull(config.cwd());
        assertNull(def.description());
    }

    @Test
    void resolveScript_returnsPathRelativeToSourceDir() {
        String json = """
            {
              "version": 1,
              "id": "test",
              "name": "Test",
              "tools": ["git_push"],
              "hooks": {
                "pre": {
                  "bash": "scripts/pre-hook.sh"
                }
              }
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        HookDefinition def = HookRegistry.parseDefinition(root, DUMMY_DIR);

        Path resolved = def.resolveScript(HookTrigger.PRE);
        assertNotNull(resolved);
        assertEquals(DUMMY_DIR.resolve("scripts/pre-hook.sh"), resolved);
    }

    @Test
    void triggerFromJsonKey_roundTrips() {
        for (HookTrigger trigger : HookTrigger.values()) {
            assertEquals(trigger, HookTrigger.fromJsonKey(trigger.jsonKey()));
        }
    }
}
