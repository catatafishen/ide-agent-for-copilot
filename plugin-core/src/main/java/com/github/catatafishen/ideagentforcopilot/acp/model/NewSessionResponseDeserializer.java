package com.github.catatafishen.ideagentforcopilot.acp.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the fact that ACP agents send {@code models} and {@code modes} in different formats:
 * <ul>
 *   <li><b>models</b>: Copilot sends a JSON array; others send a JSON object keyed by model ID.</li>
 *   <li><b>modes</b>: Kiro/Junie send a JSON object with string values; others send full objects.</li>
 * </ul>
 */
public class NewSessionResponseDeserializer implements JsonDeserializer<NewSessionResponse> {

    @Override
    public NewSessionResponse deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx)
            throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();

        String sessionId = getString(obj, "sessionId");
        List<Model> models = parseModels(obj.get("models"), ctx);
        List<NewSessionResponse.AvailableMode> modes = parseModes(obj.get("modes"), ctx);

        List<NewSessionResponse.AvailableCommand> commands = null;
        if (obj.has("commands") && obj.get("commands").isJsonArray()) {
            commands = new ArrayList<>();
            for (JsonElement e : obj.getAsJsonArray("commands")) {
                commands.add(ctx.deserialize(e, NewSessionResponse.AvailableCommand.class));
            }
        }

        List<NewSessionResponse.SessionConfigOption> configOptions = null;
        if (obj.has("configOptions") && obj.get("configOptions").isJsonArray()) {
            configOptions = new ArrayList<>();
            for (JsonElement e : obj.getAsJsonArray("configOptions")) {
                configOptions.add(ctx.deserialize(e, NewSessionResponse.SessionConfigOption.class));
            }
        }

        return new NewSessionResponse(sessionId, models, modes, commands, configOptions);
    }

    @Nullable
    private static List<Model> parseModels(@Nullable JsonElement element, JsonDeserializationContext ctx) {
        if (element == null || element.isJsonNull()) return null;

        List<Model> result = new ArrayList<>();
        if (element.isJsonArray()) {
            // Copilot: [{id, name, ...}, ...]
            for (JsonElement e : element.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    result.add(ctx.deserialize(e, Model.class));
                }
            }
        } else if (element.isJsonObject()) {
            // Others: {"model-id": {name, ...}, ...}
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    Model m = ctx.deserialize(entry.getValue(), Model.class);
                    // Use map key as id fallback if the value doesn't carry it
                    if (m.id() == null) {
                        m = new Model(entry.getKey(), m.name(), m.description(), m._meta());
                    }
                    result.add(m);
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    @Nullable
    private static List<NewSessionResponse.AvailableMode> parseModes(@Nullable JsonElement element,
                                                                      JsonDeserializationContext ctx) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) return null;

        List<NewSessionResponse.AvailableMode> result = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                // Kiro/Junie: {"slug": "Display Name"}
                result.add(new NewSessionResponse.AvailableMode(key, value.getAsString(), null));
            } else if (value.isJsonObject()) {
                NewSessionResponse.AvailableMode mode = ctx.deserialize(value, NewSessionResponse.AvailableMode.class);
                if (mode.slug() == null) {
                    mode = new NewSessionResponse.AvailableMode(key, mode.name(), mode.description());
                }
                result.add(mode);
            }
        }
        return result.isEmpty() ? null : result;
    }

    @Nullable
    private static String getString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }
}
