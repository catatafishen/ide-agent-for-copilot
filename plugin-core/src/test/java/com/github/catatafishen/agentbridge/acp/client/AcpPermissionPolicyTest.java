package com.github.catatafishen.agentbridge.acp.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ACP permission policy")
class AcpPermissionPolicyTest {

    @Test
    void onlyWebToolsAreAllowedBuiltIns() {
        assertTrue(AcpClient.isAllowedBuiltInTool("web_fetch"));
        assertTrue(AcpClient.isAllowedBuiltInTool("web_search"));
        assertFalse(AcpClient.isAllowedBuiltInTool("edit"));
        assertFalse(AcpClient.isAllowedBuiltInTool("view"));
        assertFalse(AcpClient.isAllowedBuiltInTool("bash"));
    }

    @Test
    void editViewBashAreNotAllowedBuiltIns() {
        assertFalse(AcpClient.isAllowedBuiltInTool("edit"));
        assertFalse(AcpClient.isAllowedBuiltInTool("view"));
        assertFalse(AcpClient.isAllowedBuiltInTool("bash"));
    }
}
