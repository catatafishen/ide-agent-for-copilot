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
                state.getProfiles().add(ProfileEntry.fromProfile(profile));
            }
        }
        return state;
    }

    @Override
    public void loadState(@NotNull ProfileState state) {
        synchronized (this) {
            profiles.clear();
            for (ProfileEntry entry : state.getProfiles()) {
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
        p.setBinaryName(COPILOT_PROFILE_ID);
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
        p.setBinaryName(OPENCODE_PROFILE_ID);
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
        p.setDescription("""
            Experimental profile — requires the claude-code-acp adapter \
            (npm install -g @zed-industries/claude-code-acp). \
            MCP tools are NOT auto-injected for this profile. \
            To use IDE tools with Claude Code, add the MCP server manually:
              claude mcp add intellij-ide-tools -s project -- java -jar <path-to-mcp-server.jar> --port <port>
            See docs/STANDALONE-MCP.md for detailed setup instructions.""");
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
        private List<ProfileEntry> profiles = new ArrayList<>();

        public List<ProfileEntry> getProfiles() {
            return profiles;
        }

        public void setProfiles(List<ProfileEntry> profiles) {
            this.profiles = profiles;
        }
    }

    /**
     * Flat serializable representation of an {@link AgentProfile}.
     * Uses primitive types and strings for XML serialization compatibility.
     */
    public static final class ProfileEntry {
        private String id = "";
        private String displayName = "";
        private boolean builtIn;
        private boolean experimental;
        private String description = "";
        private String binaryName = "";
        private String alternateNames = "";
        private String installHint = "";
        private String customBinaryPath = "";
        private String acpArgs = "";
        private String mcpMethod = "CONFIG_FLAG";
        private String mcpConfigTemplate = "";
        private String mcpEnvVarName = "";
        private boolean supportsModelFlag = true;
        private boolean supportsConfigDir = true;
        private boolean supportsMcpConfigFlag = true;
        private boolean requiresResourceDuplication;
        private String modelUsageField = "";
        private String agentsDirectory = "";
        private boolean ensureCopilotAgents;
        private String prependInstructionsTo = "";
        private boolean usePluginPermissions = true;
        private boolean excludeAgentBuiltInTools;
        private String permissionInjectionMethod = "NONE";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public boolean isBuiltIn() {
            return builtIn;
        }

        public void setBuiltIn(boolean builtIn) {
            this.builtIn = builtIn;
        }

        public boolean isExperimental() {
            return experimental;
        }

        public void setExperimental(boolean experimental) {
            this.experimental = experimental;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getBinaryName() {
            return binaryName;
        }

        public void setBinaryName(String binaryName) {
            this.binaryName = binaryName;
        }

        public String getAlternateNames() {
            return alternateNames;
        }

        public void setAlternateNames(String alternateNames) {
            this.alternateNames = alternateNames;
        }

        public String getInstallHint() {
            return installHint;
        }

        public void setInstallHint(String installHint) {
            this.installHint = installHint;
        }

        public String getCustomBinaryPath() {
            return customBinaryPath;
        }

        public void setCustomBinaryPath(String customBinaryPath) {
            this.customBinaryPath = customBinaryPath;
        }

        public String getAcpArgs() {
            return acpArgs;
        }

        public void setAcpArgs(String acpArgs) {
            this.acpArgs = acpArgs;
        }

        public String getMcpMethod() {
            return mcpMethod;
        }

        public void setMcpMethod(String mcpMethod) {
            this.mcpMethod = mcpMethod;
        }

        public String getMcpConfigTemplate() {
            return mcpConfigTemplate;
        }

        public void setMcpConfigTemplate(String mcpConfigTemplate) {
            this.mcpConfigTemplate = mcpConfigTemplate;
        }

        public String getMcpEnvVarName() {
            return mcpEnvVarName;
        }

        public void setMcpEnvVarName(String mcpEnvVarName) {
            this.mcpEnvVarName = mcpEnvVarName;
        }

        public boolean isSupportsModelFlag() {
            return supportsModelFlag;
        }

        public void setSupportsModelFlag(boolean supportsModelFlag) {
            this.supportsModelFlag = supportsModelFlag;
        }

        public boolean isSupportsConfigDir() {
            return supportsConfigDir;
        }

        public void setSupportsConfigDir(boolean supportsConfigDir) {
            this.supportsConfigDir = supportsConfigDir;
        }

        public boolean isSupportsMcpConfigFlag() {
            return supportsMcpConfigFlag;
        }

        public void setSupportsMcpConfigFlag(boolean supportsMcpConfigFlag) {
            this.supportsMcpConfigFlag = supportsMcpConfigFlag;
        }

        public boolean isRequiresResourceDuplication() {
            return requiresResourceDuplication;
        }

        public void setRequiresResourceDuplication(boolean v) {
            this.requiresResourceDuplication = v;
        }

        public String getModelUsageField() {
            return modelUsageField;
        }

        public void setModelUsageField(String modelUsageField) {
            this.modelUsageField = modelUsageField;
        }

        public String getAgentsDirectory() {
            return agentsDirectory;
        }

        public void setAgentsDirectory(String agentsDirectory) {
            this.agentsDirectory = agentsDirectory;
        }

        public boolean isEnsureCopilotAgents() {
            return ensureCopilotAgents;
        }

        public void setEnsureCopilotAgents(boolean ensureCopilotAgents) {
            this.ensureCopilotAgents = ensureCopilotAgents;
        }

        public String getPrependInstructionsTo() {
            return prependInstructionsTo;
        }

        public void setPrependInstructionsTo(String prependInstructionsTo) {
            this.prependInstructionsTo = prependInstructionsTo;
        }

        public boolean isUsePluginPermissions() {
            return usePluginPermissions;
        }

        public void setUsePluginPermissions(boolean usePluginPermissions) {
            this.usePluginPermissions = usePluginPermissions;
        }

        public boolean isExcludeAgentBuiltInTools() {
            return excludeAgentBuiltInTools;
        }

        public void setExcludeAgentBuiltInTools(boolean excludeAgentBuiltInTools) {
            this.excludeAgentBuiltInTools = excludeAgentBuiltInTools;
        }

        public String getPermissionInjectionMethod() {
            return permissionInjectionMethod;
        }

        public void setPermissionInjectionMethod(String permissionInjectionMethod) {
            this.permissionInjectionMethod = permissionInjectionMethod;
        }

        @NotNull
        static ProfileEntry fromProfile(@NotNull AgentProfile p) {
            ProfileEntry e = new ProfileEntry();
            e.setId(p.getId());
            e.setDisplayName(p.getDisplayName());
            e.setBuiltIn(p.isBuiltIn());
            e.setExperimental(p.isExperimental());
            e.setDescription(p.getDescription() != null ? p.getDescription() : "");
            e.setBinaryName(p.getBinaryName());
            e.setAlternateNames(String.join(",", p.getAlternateNames()));
            e.setInstallHint(p.getInstallHint());
            e.setCustomBinaryPath(p.getCustomBinaryPath());
            e.setAcpArgs(String.join(" ", p.getAcpArgs()));
            e.setMcpMethod(p.getMcpMethod().name());
            e.setMcpConfigTemplate(p.getMcpConfigTemplate());
            e.setMcpEnvVarName(p.getMcpEnvVarName());
            e.setSupportsModelFlag(p.isSupportsModelFlag());
            e.setSupportsConfigDir(p.isSupportsConfigDir());
            e.setSupportsMcpConfigFlag(p.isSupportsMcpConfigFlag());
            e.setRequiresResourceDuplication(p.isRequiresResourceDuplication());
            e.setModelUsageField(p.getModelUsageField() != null ? p.getModelUsageField() : "");
            e.setAgentsDirectory(p.getAgentsDirectory() != null ? p.getAgentsDirectory() : "");
            e.setEnsureCopilotAgents(p.isEnsureCopilotAgents());
            e.setPrependInstructionsTo(p.getPrependInstructionsTo() != null ? p.getPrependInstructionsTo() : "");
            e.setUsePluginPermissions(p.isUsePluginPermissions());
            e.setExcludeAgentBuiltInTools(p.isExcludeAgentBuiltInTools());
            e.setPermissionInjectionMethod(p.getPermissionInjectionMethod().name());
            return e;
        }

        @NotNull
        AgentProfile toProfile() {
            AgentProfile p = new AgentProfile();
            p.setId(getId());
            p.setDisplayName(getDisplayName());
            p.setBuiltIn(isBuiltIn());
            p.setExperimental(isExperimental());
            p.setDescription(getDescription().isEmpty() ? null : getDescription());
            p.setBinaryName(getBinaryName());
            p.setAlternateNames(splitComma(getAlternateNames()));
            p.setInstallHint(getInstallHint());
            p.setCustomBinaryPath(getCustomBinaryPath());
            p.setAcpArgs(splitSpace(getAcpArgs()));
            try {
                p.setMcpMethod(McpInjectionMethod.valueOf(getMcpMethod()));
            } catch (IllegalArgumentException e) {
                p.setMcpMethod(McpInjectionMethod.CONFIG_FLAG);
            }
            p.setMcpConfigTemplate(getMcpConfigTemplate());
            p.setMcpEnvVarName(getMcpEnvVarName());
            p.setSupportsModelFlag(isSupportsModelFlag());
            p.setSupportsConfigDir(isSupportsConfigDir());
            p.setSupportsMcpConfigFlag(isSupportsMcpConfigFlag());
            p.setRequiresResourceDuplication(isRequiresResourceDuplication());
            p.setModelUsageField(getModelUsageField());
            p.setAgentsDirectory(getAgentsDirectory().isEmpty() ? null : getAgentsDirectory());
            p.setEnsureCopilotAgents(isEnsureCopilotAgents());
            p.setPrependInstructionsTo(getPrependInstructionsTo().isEmpty() ? null : getPrependInstructionsTo());
            p.setUsePluginPermissions(isUsePluginPermissions());
            p.setExcludeAgentBuiltInTools(isExcludeAgentBuiltInTools());
            try {
                p.setPermissionInjectionMethod(PermissionInjectionMethod.valueOf(getPermissionInjectionMethod()));
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
