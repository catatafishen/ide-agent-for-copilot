package com.github.catatafishen.agentbridge.psi.tools.memory;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for memory tools. Lives in the same package to access
 * package-private constructors — memory tools are intentionally hidden behind
 * {@link MemoryToolFactory} from outside the package.
 */
@DisplayName("Memory tool metadata contracts")
class MemoryToolContractTest {

    static Stream<Tool> memoryTools() {
        return Stream.of(
            new MemorySearchTool(null),
            new MemoryStoreTool(null),
            new MemoryStatusTool(null),
            new MemoryWakeUpTool(null),
            new MemoryRecallTool(null),
            new MemoryKgQueryTool(null),
            new MemoryKgAddTool(null),
            new MemoryKgInvalidateTool(null),
            new MemoryKgTimelineTool(null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryTools")
    @DisplayName("id() is non-empty and snake_case")
    void idIsNonEmpty(Tool tool) {
        String id = tool.id();
        assertNotNull(id, "id() must not be null");
        assertFalse(id.isBlank(), "id() must not be blank");
        assertTrue(id.matches("[a-z][a-z0-9_]*"),
            tool.getClass().getSimpleName() + ": id must be snake_case, got: " + id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryTools")
    @DisplayName("displayName() is non-empty")
    void displayNameIsNonEmpty(Tool tool) {
        String name = tool.displayName();
        assertNotNull(name, "displayName() must not be null");
        assertFalse(name.isBlank(), "displayName() must not be blank");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryTools")
    @DisplayName("description() is non-empty and ends with period")
    void descriptionIsNonEmpty(Tool tool) {
        String desc = tool.description();
        assertNotNull(desc, "description() must not be null");
        assertFalse(desc.isBlank(), "description() must not be blank");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryTools")
    @DisplayName("kind() returns a valid Kind")
    void kindIsValid(Tool tool) {
        ToolDefinition.Kind kind = tool.kind();
        assertNotNull(kind, "kind() must not be null");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryTools")
    @DisplayName("category() is not null")
    void categoryIsNotNull(Tool tool) {
        assertNotNull(tool.category(), "category() must not be null");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryTools")
    @DisplayName("inputSchema() returns a valid MCP schema object")
    void inputSchemaIsValid(Tool tool) {
        JsonObject schema = tool.inputSchema();
        assertNotNull(schema, "inputSchema() must not be null");
        assertEquals("object", schema.get("type").getAsString(), "schema root type must be 'object'");
        assertTrue(schema.has("properties"), "schema must have 'properties'");
        assertTrue(schema.get("properties").isJsonObject(), "'properties' must be a JsonObject");
        assertTrue(schema.has("required"), "schema must have 'required'");
        assertTrue(schema.get("required").isJsonArray(), "'required' must be a JsonArray");

        JsonObject props = schema.getAsJsonObject("properties");
        JsonArray required = schema.getAsJsonArray("required");

        for (var entry : props.entrySet()) {
            JsonObject prop = entry.getValue().getAsJsonObject();
            assertTrue(prop.has("type"),
                tool.id() + ": property '" + entry.getKey() + "' must have 'type'");
            assertTrue(prop.has("description"),
                tool.id() + ": property '" + entry.getKey() + "' must have 'description'");
        }

        for (var reqEntry : required) {
            String reqName = reqEntry.getAsString();
            assertTrue(props.has(reqName),
                tool.id() + ": required param '" + reqName + "' missing from properties");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryTools")
    @DisplayName("isReadOnly() is consistent with isDestructive()")
    void readOnlyConsistency(Tool tool) {
        if (tool.isDestructive()) {
            assertFalse(tool.isReadOnly(),
                tool.id() + ": destructive tools cannot be read-only");
        }
    }
}
