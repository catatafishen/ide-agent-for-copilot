package com.github.catatafishen.agentbridge.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PermissionDefaults")
class PermissionDefaultsTest {

    // ── STANDARD preset ─────────────────────────────────────────────────

    @Test
    @DisplayName("STANDARD: READ is ALLOW")
    void standard_readAllowed() {
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.STANDARD.forCategory(ToolCategory.READ));
    }

    @Test
    @DisplayName("STANDARD: EDIT is ASK")
    void standard_editAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.STANDARD.forCategory(ToolCategory.EDIT));
    }

    @Test
    @DisplayName("STANDARD: EXECUTE is ASK")
    void standard_executeAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.STANDARD.forCategory(ToolCategory.EXECUTE));
    }

    @Test
    @DisplayName("STANDARD: GIT_READ is ALLOW")
    void standard_gitReadAllowed() {
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.STANDARD.forCategory(ToolCategory.GIT_READ));
    }

    @Test
    @DisplayName("STANDARD: GIT_WRITE is ASK")
    void standard_gitWriteAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.STANDARD.forCategory(ToolCategory.GIT_WRITE));
    }

    @Test
    @DisplayName("STANDARD: DESTRUCTIVE is DENY")
    void standard_destructiveDenied() {
        assertEquals(ToolPermission.DENY, PermissionDefaults.STANDARD.forCategory(ToolCategory.DESTRUCTIVE));
    }

    @Test
    @DisplayName("STANDARD: OTHER is ASK")
    void standard_otherAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.STANDARD.forCategory(ToolCategory.OTHER));
    }

    // ── PERMISSIVE preset ───────────────────────────────────────────────

    @Test
    @DisplayName("PERMISSIVE: all categories except DESTRUCTIVE are ALLOW")
    void permissive_allExceptDestructive() {
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.READ));
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.EDIT));
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.EXECUTE));
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.GIT_READ));
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.GIT_WRITE));
    }

    @Test
    @DisplayName("PERMISSIVE: OTHER is ASK (hardcoded fallback)")
    void permissive_otherAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.OTHER));
    }

    @Test
    @DisplayName("PERMISSIVE: DESTRUCTIVE is ASK")
    void permissive_destructiveAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.DESTRUCTIVE));
    }

    // ── Custom instance ─────────────────────────────────────────────────

    @Test
    @DisplayName("Custom PermissionDefaults: forCategory returns configured values")
    void custom_forCategory() {
        PermissionDefaults custom = new PermissionDefaults(
            ToolPermission.DENY,
            ToolPermission.DENY,
            ToolPermission.ALLOW,
            ToolPermission.ASK,
            ToolPermission.DENY,
            ToolPermission.ALLOW
        );
        assertEquals(ToolPermission.DENY, custom.forCategory(ToolCategory.READ));
        assertEquals(ToolPermission.DENY, custom.forCategory(ToolCategory.EDIT));
        assertEquals(ToolPermission.ALLOW, custom.forCategory(ToolCategory.EXECUTE));
        assertEquals(ToolPermission.ASK, custom.forCategory(ToolCategory.GIT_READ));
        assertEquals(ToolPermission.DENY, custom.forCategory(ToolCategory.GIT_WRITE));
        assertEquals(ToolPermission.ALLOW, custom.forCategory(ToolCategory.DESTRUCTIVE));
        // OTHER always returns ASK regardless of custom configuration
        assertEquals(ToolPermission.ASK, custom.forCategory(ToolCategory.OTHER));
    }
}
