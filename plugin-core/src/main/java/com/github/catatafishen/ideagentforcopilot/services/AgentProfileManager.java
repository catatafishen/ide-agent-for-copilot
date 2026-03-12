package com.github.catatafishen.ideagentforcopilot.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application-level service that manages all agent profiles.
 * Persists user-created and modified profiles; ships built-in defaults
 * for known agents (Copilot, OpenCode).
 *
 * <p>Thread-safe: all mutations are synchronized on this instance.</p>
 */
@Service(Service.Level.APP)
@State(name = "AgentProfiles", storages = @Storage("agentProfiles.xml"))
public final class AgentProfileManager implements PersistentStateComponent<AgentProfileManager.ProfileState> {

    private static final Logger LOG = Logger.getInstance(AgentProfileManager.class);

    public static final String COPILOT_PROFILE_ID = "copilot";
    public static final String OPENCODE_PROFILE_ID = "opencode";
    public static final String CLAUDE_CODE_PROFILE_ID = "claude-code";

    private final Map<String, AgentProfile> profiles = new LinkedHashMap<>();

    public AgentProfileManager() {
        ensureDefaults();
    }

    @NotNull
    public static AgentProfileManager getInstance() {
        return ApplicationManager.getApplication().getService(AgentProfileManager.class);
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @NotNull
    public synchronized List<AgentProfile> getAllProfiles() {
        ensureDefaults();
        return new ArrayList<>(profiles.values());
    }

    @Nullable
    public synchronized AgentProfile getProfile(@NotNull String id) {
        ensureDefaults();
        return profiles.get(id);
    }

    public synchronized void addProfile(@NotNull AgentProfile profile) {
        profiles.put(profile.getId(), profile);
        LOG.info("Added agent profile: " + profile.getDisplayName() + " (" + profile.getId() + ")");
    }

    public synchronized void updateProfile(@NotNull AgentProfile profile) {
        if (!profiles.containsKey(profile.getId())) {
            LOG.warn("Cannot update non-existent profile: " + profile.getId());
            return;
        }
        profiles.put(profile.getId(), profile);
        LOG.info("Updated agent profile: " + profile.getDisplayName());
    }

    public synchronized void removeProfile(@NotNull String id) {
        AgentProfile profile = profiles.get(id);
        if (profile != null && profile.isBuiltIn()) {
            LOG.warn("Cannot remove built-in profile: " + id);
            return;
        }
        profiles.remove(id);
        LOG.info("Removed agent profile: " + id);
    }

    /**
     * Resets a built-in profile to its factory defaults.
     */
    public synchronized void resetToDefaults(@NotNull String id) {
        AgentProfile defaults = createDefaultProfile(id);
        if (defaults != null) {
            profiles.put(id, defaults);
            LOG.info("Reset profile to defaults: " + id);
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Override
    public @Nullable ProfileState getState() {
        ProfileState state = new ProfileState();
        synchronized (this) {
            for (AgentProfile profile : profiles.values()) {
                state.profiles.add(ProfileEntry.fromProfile(profile));
            }
        }
        return state;
    }

    @Override
    public void loadState(@NotNull ProfileState state) {
        synchronized (this) {
            profiles.clear();
            for (ProfileEntry entry : state.profiles) {
                AgentProfile profile = entry.toProfile();
                profiles.put(profile.getId(), profile);
            }
            ensureDefaults();
        }
    }

    private void ensureDefaults() {
        if (!profiles.containsKey(COPILOT_PROFILE_ID)) {
            profiles.put(COPILOT_PROFILE_ID, createCopilotProfile());
        } else {
            refreshBuiltInProfile(COPILOT_PROFILE_ID);
        }
        if (!profiles.containsKey(OPENCODE_PROFILE_ID)) {
            profiles.put(OPENCODE_PROFILE_ID, createOpenCodeProfile());
        } else {
            refreshBuiltInProfile(OPENCODE_PROFILE_ID);
        }
        if (!profiles.containsKey(CLAUDE_CODE_PROFILE_ID)) {
            profiles.put(CLAUDE_CODE_PROFILE_ID, createClaudeCodeProfile());
        } else {
            refreshBuiltInProfile(CLAUDE_CODE_PROFILE_ID);
        }
    }

    private void refreshBuiltInProfile(@NotNull String id) {
        AgentProfile stored = profiles.get(id);
        AgentProfile defaults = createDefaultProfile(id);
        if (stored == null || defaults == null || !stored.isBuiltIn()) return;

        stored.setExperimental(defaults.isExperimental());
        stored.setDescription(defaults.getDescription());
        stored.setAcpArgs(defaults.getAcpArgs());
        stored.setMcpConfigTemplate(defaults.getMcpConfigTemplate());
        stored.setMcpMethod(defaults.getMcpMethod());
        stored.setMcpEnvVarName(defaults.getMcpEnvVarName());
        stored.setInstallHint(defaults.getInstallHint());
        stored.setSupportsMcpConfigFlag(defaults.isSupportsMcpConfigFlag());
        stored.setSupportsModelFlag(defaults.isSupportsModelFlag());
        stored.setSupportsConfigDir(defaults.isSupportsConfigDir());
        stored.setAgentsDirectory(defaults.getAgentsDirectory());
        stored.setRequiresResourceDuplication(defaults.isRequiresResourceDuplication());
        stored.setExcludeAgentBuiltInTools(defaults.isExcludeAgentBuiltInTools());
        stored.setUsePluginPermissions(defaults.isUsePluginPermissions());
        stored.setPermissionInjectionMethod(defaults.getPermissionInjectionMethod());
        stored.setModelUsageField(defaults.getModelUsageField());
        stored.setEnsureCopilotAgents(defaults.isEnsureCopilotAgents());
        // Only overwrite instructions target if not user-customized (null/empty means never set)
        if (stored.getPrependInstructionsTo() == null || stored.getPrependInstructionsTo().isEmpty()) {
            stored.setPrependInstructionsTo(defaults.getPrependInstructionsTo());
        }
    }

    @Nullable
    private AgentProfile createDefaultProfile(@NotNull String id) {
        return switch (id) {
            case COPILOT_PROFILE_ID -> createCopilotProfile();
            case OPENCODE_PROFILE_ID -> createOpenCodeProfile();
            case CLAUDE_CODE_PROFILE_ID -> createClaudeCodeProfile();
            default -> null;
        };
    }

    // ── Default Profiles ─────────────────────────────────────────────────────

    /**
     * Creates the default Copilot profile. Public for use in tests.
     */
    @NotNull
    public static AgentProfile createDefaultCopilotProfile() {
        return createCopilotProfile();
    }

    /**
     * Creates the default OpenCode profile. Public for use in tests.
     */
    @NotNull
    public static AgentProfile createDefaultOpenCodeProfile() {
        return createOpenCodeProfile();
    }

    /**
     * Creates the default Claude Code profile. Public for use in tests.
     */
    @NotNull
    public static AgentProfile createDefaultClaudeCodeProfile() {
        return createClaudeCodeProfile();
    }

    @NotNull
    private static AgentProfile createCopilotProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(COPILOT_PROFILE_ID);
        p.setDisplayName("GitHub Copilot");
        p.setBuiltIn(true);
        p.setBinaryName("copilot");
        p.setAlternateNames(List.of("copilot-cli"));
        p.setInstallHint("Install with: npm install -g @anthropic-ai/copilot-cli");
        p.setAcpArgs(List.of("--acp", "--stdio"));
        p.setMcpMethod(McpInjectionMethod.CONFIG_FLAG);
        p.setSupportsMcpConfigFlag(true);
        p.setMcpConfigTemplate(
            "{\"mcpServers\":{\"intellij-code-tools\":"
                + "{\"type\":\"http\","
                + "\"url\":\"http://localhost:{mcpPort}/mcp\"}}}");
        p.setSupportsModelFlag(true);
        p.setSupportsConfigDir(true);
        p.setRequiresResourceDuplication(true);
        p.setModelUsageField("copilotUsage");
        p.setAgentsDirectory(".github/agents");
        p.setEnsureCopilotAgents(true);
        p.setPrependInstructionsTo(".copilot/copilot-instructions.md");
        p.setPermissionInjectionMethod(PermissionInjectionMethod.CLI_FLAGS);
        return p;
    }

    @NotNull
    private static AgentProfile createOpenCodeProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(OPENCODE_PROFILE_ID);
        p.setDisplayName("OpenCode");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setDescription("Experimental profile — OpenCode ACP support is community-maintained. "
            + "Install: npm i -g opencode-ai");
        p.setBinaryName("opencode");
        p.setInstallHint("Install with: npm i -g opencode-ai");
        p.setAcpArgs(List.of("acp"));
        p.setMcpMethod(McpInjectionMethod.ENV_VAR);
        p.setMcpEnvVarName("OPENCODE_CONFIG_CONTENT");
        p.setMcpConfigTemplate(
            "{\"mcp\":{\"intellij-code-tools\":"
                + "{\"type\":\"local\","
                + "\"command\":[\"{javaPath}\",\"-jar\",\"{mcpJarPath}\","
                + "\"--port\",\"{mcpPort}\"]}}}");
        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(false);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(false);
        p.setExcludeAgentBuiltInTools(true);
        p.setUsePluginPermissions(false);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.CONFIG_JSON);
        return p;
    }

    @NotNull
    private static AgentProfile createClaudeCodeProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(CLAUDE_CODE_PROFILE_ID);
        p.setDisplayName("Claude Code");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setDescription("Experimental profile — requires the claude-code-acp adapter "
            + "(npm install -g @zed-industries/claude-code-acp). "
            + "MCP tools are NOT auto-injected for this profile. "
            + "To use IDE tools with Claude Code, add the MCP server manually:\n"
            + "  claude mcp add intellij-ide-tools -s project -- java -jar <path-to-mcp-server.jar> --port <port>\n"
            + "See docs/STANDALONE-MCP.md for detailed setup instructions.");
        p.setBinaryName("claude-code-acp");
        p.setAlternateNames(List.of());
        p.setInstallHint("Install with: npm install -g @zed-industries/claude-code-acp");
        p.setAcpArgs(List.of());
        p.setMcpMethod(McpInjectionMethod.NONE);
        p.setSupportsMcpConfigFlag(false);
        p.setSupportsModelFlag(false);
        p.setSupportsConfigDir(false);
        p.setRequiresResourceDuplication(false);
        p.setExcludeAgentBuiltInTools(false);
        p.setUsePluginPermissions(true);
        p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
        return p;
    }

    // ── Serialization model ──────────────────────────────────────────────────

    /**
     * Serializable state wrapper for {@link PersistentStateComponent}.
     */
    public static final class ProfileState {
        public List<ProfileEntry> profiles = new ArrayList<>();
    }

    /**
     * Flat serializable representation of an {@link AgentProfile}.
     * Uses primitive types and strings for XML serialization compatibility.
     */
    public static final class ProfileEntry {
        public String id = "";
        public String displayName = "";
        public boolean builtIn;
        public boolean experimental;
        public String description = "";
        public String binaryName = "";
        public String alternateNames = "";
        public String installHint = "";
        public String customBinaryPath = "";
        public String acpArgs = "";
        public String mcpMethod = "CONFIG_FLAG";
        public String mcpConfigTemplate = "";
        public String mcpEnvVarName = "";
        public boolean supportsModelFlag = true;
        public boolean supportsConfigDir = true;
        public boolean supportsMcpConfigFlag = true;
        public boolean requiresResourceDuplication;
        public String modelUsageField = "";
        public String agentsDirectory = "";
        public boolean ensureCopilotAgents;
        public String prependInstructionsTo = "";
        public boolean usePluginPermissions = true;
        public boolean excludeAgentBuiltInTools;
        public String permissionInjectionMethod = "NONE";

        @NotNull
        static ProfileEntry fromProfile(@NotNull AgentProfile p) {
            ProfileEntry e = new ProfileEntry();
            e.id = p.getId();
            e.displayName = p.getDisplayName();
            e.builtIn = p.isBuiltIn();
            e.experimental = p.isExperimental();
            e.description = p.getDescription() != null ? p.getDescription() : "";
            e.binaryName = p.getBinaryName();
            e.alternateNames = String.join(",", p.getAlternateNames());
            e.installHint = p.getInstallHint();
            e.customBinaryPath = p.getCustomBinaryPath();
            e.acpArgs = String.join(" ", p.getAcpArgs());
            e.mcpMethod = p.getMcpMethod().name();
            e.mcpConfigTemplate = p.getMcpConfigTemplate();
            e.mcpEnvVarName = p.getMcpEnvVarName();
            e.supportsModelFlag = p.isSupportsModelFlag();
            e.supportsConfigDir = p.isSupportsConfigDir();
            e.supportsMcpConfigFlag = p.isSupportsMcpConfigFlag();
            e.requiresResourceDuplication = p.isRequiresResourceDuplication();
            e.modelUsageField = p.getModelUsageField() != null ? p.getModelUsageField() : "";
            e.agentsDirectory = p.getAgentsDirectory() != null ? p.getAgentsDirectory() : "";
            e.ensureCopilotAgents = p.isEnsureCopilotAgents();
            e.prependInstructionsTo = p.getPrependInstructionsTo() != null ? p.getPrependInstructionsTo() : "";
            e.usePluginPermissions = p.isUsePluginPermissions();
            e.excludeAgentBuiltInTools = p.isExcludeAgentBuiltInTools();
            e.permissionInjectionMethod = p.getPermissionInjectionMethod().name();
            return e;
        }

        @NotNull
        AgentProfile toProfile() {
            AgentProfile p = new AgentProfile();
            p.setId(id);
            p.setDisplayName(displayName);
            p.setBuiltIn(builtIn);
            p.setExperimental(experimental);
            p.setDescription(description.isEmpty() ? null : description);
            p.setBinaryName(binaryName);
            p.setAlternateNames(splitComma(alternateNames));
            p.setInstallHint(installHint);
            p.setCustomBinaryPath(customBinaryPath);
            p.setAcpArgs(splitSpace(acpArgs));
            try {
                p.setMcpMethod(McpInjectionMethod.valueOf(mcpMethod));
            } catch (IllegalArgumentException e) {
                p.setMcpMethod(McpInjectionMethod.CONFIG_FLAG);
            }
            p.setMcpConfigTemplate(mcpConfigTemplate);
            p.setMcpEnvVarName(mcpEnvVarName);
            p.setSupportsModelFlag(supportsModelFlag);
            p.setSupportsConfigDir(supportsConfigDir);
            p.setSupportsMcpConfigFlag(supportsMcpConfigFlag);
            p.setRequiresResourceDuplication(requiresResourceDuplication);
            p.setModelUsageField(modelUsageField);
            p.setAgentsDirectory(agentsDirectory.isEmpty() ? null : agentsDirectory);
            p.setEnsureCopilotAgents(ensureCopilotAgents);
            p.setPrependInstructionsTo(prependInstructionsTo.isEmpty() ? null : prependInstructionsTo);
            p.setUsePluginPermissions(usePluginPermissions);
            p.setExcludeAgentBuiltInTools(excludeAgentBuiltInTools);
            try {
                p.setPermissionInjectionMethod(PermissionInjectionMethod.valueOf(permissionInjectionMethod));
            } catch (IllegalArgumentException e) {
                p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
            }
            return p;
        }

        @NotNull
        private static List<String> splitComma(@NotNull String s) {
            if (s.isEmpty()) return new ArrayList<>();
            List<String> result = new ArrayList<>();
            for (String part : s.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
            return result;
        }

        @NotNull
        private static List<String> splitSpace(@NotNull String s) {
            if (s.isEmpty()) return new ArrayList<>();
            List<String> result = new ArrayList<>();
            for (String part : s.split("\\s+")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
            return result;
        }

    }
}
