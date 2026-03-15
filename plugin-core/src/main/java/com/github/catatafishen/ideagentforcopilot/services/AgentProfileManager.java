package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.bridge.AnthropicDirectClient;
import com.github.catatafishen.ideagentforcopilot.bridge.ClaudeCliClient;
import com.github.catatafishen.ideagentforcopilot.bridge.ClaudeCliCredentials;
import com.github.catatafishen.ideagentforcopilot.bridge.CopilotAcpClient;
import com.github.catatafishen.ideagentforcopilot.bridge.JunieAcpClient;
import com.github.catatafishen.ideagentforcopilot.bridge.KiroAcpClient;
import com.github.catatafishen.ideagentforcopilot.bridge.OpenCodeAcpClient;
import com.github.catatafishen.ideagentforcopilot.bridge.TransportType;
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

    public static final String COPILOT_PROFILE_ID = CopilotAcpClient.PROFILE_ID;
    public static final String OPENCODE_PROFILE_ID = OpenCodeAcpClient.PROFILE_ID;
    public static final String CLAUDE_CODE_PROFILE_ID = AnthropicDirectClient.PROFILE_ID;
    public static final String CLAUDE_CLI_PROFILE_ID = ClaudeCliClient.PROFILE_ID;
    public static final String JUNIE_PROFILE_ID = JunieAcpClient.PROFILE_ID;
    public static final String KIRO_PROFILE_ID = KiroAcpClient.PROFILE_ID;

    private final Map<String, AgentProfile> profiles = new LinkedHashMap<>();

    public AgentProfileManager() {
        ensureDefaults();
    }

    @NotNull
    public static AgentProfileManager getInstance() {
        return ApplicationManager.getApplication().getService(AgentProfileManager.class);
    }

    /**
     * Returns a human-readable Claude CLI authentication status string for display in settings UI.
     * Returns {@code null} if the credentials file cannot be read.
     */
    @Nullable
    public static String getClaudeCliAuthStatus() {
        ClaudeCliCredentials creds = ClaudeCliCredentials.read();
        if (creds.isLoggedIn()) {
            String name = creds.getDisplayName();
            return "✓ Logged in" + (name != null ? " as " + name : "");
        } else {
            return null;
        }
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
    public @NotNull ProfileState getState() {
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
        for (String id : List.of(COPILOT_PROFILE_ID, OPENCODE_PROFILE_ID, CLAUDE_CODE_PROFILE_ID,
                                  CLAUDE_CLI_PROFILE_ID, JUNIE_PROFILE_ID, KIRO_PROFILE_ID)) {
            if (!profiles.containsKey(id)) {
                AgentProfile profile = createDefaultProfile(id);
                if (profile != null) profiles.put(id, profile);
            } else {
                refreshBuiltInProfile(id);
            }
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
        stored.setToolNameRegex(defaults.getToolNameRegex());
        stored.setToolNameReplacement(defaults.getToolNameReplacement());
        stored.setBundledAgentFiles(defaults.getBundledAgentFiles());
        stored.setAdditionalInstructions(defaults.getAdditionalInstructions());
        stored.setInstallUrl(defaults.getInstallUrl());
        stored.setSupportsOAuthSignIn(defaults.isSupportsOAuthSignIn());
        // Only overwrite instructions target if not user-customized (null/empty means never set)
        if (stored.getPrependInstructionsTo() == null || stored.getPrependInstructionsTo().isEmpty()) {
            stored.setPrependInstructionsTo(defaults.getPrependInstructionsTo());
        }
        // Seed custom CLI models if the user has never set them (empty = use defaults)
        if (stored.getCustomCliModels().isEmpty()) {
            stored.setCustomCliModels(defaults.getCustomCliModels());
        }
    }

    @Nullable
    private AgentProfile createDefaultProfile(@NotNull String id) {
        return switch (id) {
            case COPILOT_PROFILE_ID -> CopilotAcpClient.createDefaultProfile();
            case OPENCODE_PROFILE_ID -> OpenCodeAcpClient.createDefaultProfile();
            case CLAUDE_CODE_PROFILE_ID -> AnthropicDirectClient.createDefaultProfile();
            case CLAUDE_CLI_PROFILE_ID -> ClaudeCliClient.createDefaultProfile();
            case JUNIE_PROFILE_ID -> JunieAcpClient.createDefaultProfile();
            case KIRO_PROFILE_ID -> KiroAcpClient.createDefaultProfile();
            default -> null;
        };
    }

    /**
     * Creates the default Copilot profile. Public for use in tests.
     */
    @NotNull
    public static AgentProfile createDefaultCopilotProfile() {
        return CopilotAcpClient.createDefaultProfile();
    }

    // ── Serialization model ──────────────────────────────────────────────────

    /**
     * Serializable state wrapper for {@link PersistentStateComponent}.
     */
    public static final class ProfileState {
        private final List<ProfileEntry> profiles = new ArrayList<>();

        public List<ProfileEntry> getProfiles() {
            return profiles;
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
        // kept for backward-compat deserialization (ignored on write)
        private String bundledAgentFiles = "";
        private String additionalInstructions = "";
        private String prependInstructionsTo = "";
        private boolean usePluginPermissions = true;
        private boolean excludeAgentBuiltInTools;
        private String permissionInjectionMethod = "NONE";
        private String toolNameRegex = "";
        private String toolNameReplacement = "";
        private String transportType = "ACP";

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

        public String getBundledAgentFiles() {
            return bundledAgentFiles;
        }

        public void setBundledAgentFiles(String bundledAgentFiles) {
            this.bundledAgentFiles = bundledAgentFiles != null ? bundledAgentFiles : "";
        }

        public String getAdditionalInstructions() {
            return additionalInstructions;
        }

        public void setAdditionalInstructions(String additionalInstructions) {
            this.additionalInstructions = additionalInstructions != null ? additionalInstructions : "";
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

        public String getToolNameRegex() {
            return toolNameRegex;
        }

        public void setToolNameRegex(String toolNameRegex) {
            this.toolNameRegex = toolNameRegex != null ? toolNameRegex : "";
        }

        public String getToolNameReplacement() {
            return toolNameReplacement;
        }

        public void setToolNameReplacement(String toolNameReplacement) {
            this.toolNameReplacement = toolNameReplacement != null ? toolNameReplacement : "";
        }

        public String getTransportType() {
            return transportType;
        }

        public void setTransportType(String transportType) {
            this.transportType = transportType != null ? transportType : "ACP";
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
            e.setBundledAgentFiles(String.join(",", p.getBundledAgentFiles()));
            e.setAdditionalInstructions(p.getAdditionalInstructions());
            e.setPrependInstructionsTo(p.getPrependInstructionsTo() != null ? p.getPrependInstructionsTo() : "");
            e.setUsePluginPermissions(p.isUsePluginPermissions());
            e.setExcludeAgentBuiltInTools(p.isExcludeAgentBuiltInTools());
            e.setPermissionInjectionMethod(p.getPermissionInjectionMethod().name());
            e.setToolNameRegex(p.getToolNameRegex());
            e.setToolNameReplacement(p.getToolNameReplacement());
            e.setTransportType(p.getTransportType().name());
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
            p.setBundledAgentFiles(splitComma(getBundledAgentFiles()));
            p.setAdditionalInstructions(getAdditionalInstructions());
            p.setPrependInstructionsTo(getPrependInstructionsTo().isEmpty() ? null : getPrependInstructionsTo());
            p.setUsePluginPermissions(isUsePluginPermissions());
            p.setExcludeAgentBuiltInTools(isExcludeAgentBuiltInTools());
            p.setToolNameRegex(getToolNameRegex().isEmpty() ? null : getToolNameRegex());
            p.setToolNameReplacement(getToolNameReplacement().isEmpty() ? null : getToolNameReplacement());
            try {
                p.setPermissionInjectionMethod(PermissionInjectionMethod.valueOf(getPermissionInjectionMethod()));
            } catch (IllegalArgumentException e) {
                p.setPermissionInjectionMethod(PermissionInjectionMethod.NONE);
            }
            try {
                p.setTransportType(TransportType.valueOf(getTransportType()));
            } catch (IllegalArgumentException e) {
                p.setTransportType(TransportType.ACP);
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
