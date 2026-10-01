package com.smwu.backend.ai.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code resources/prompts/}의 프롬프트와 응답 스키마를 읽는다.
 * <ul>
 *   <li>{@code {name}.txt}: 프롬프트. {@code {{변수}}} 자리를 {@link #render}로 채운다</li>
 *   <li>{@code {name}.schema.json}: 응답 JSON 스키마. 로드할 때 strict 모드 규칙을 검사한다</li>
 * </ul>
 */
@Component
public class PromptLoader {

    private static final String BASE_PATH = "prompts/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private final ObjectMapper objectMapper;
    private final Map<String, String> textCache = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> schemaCache = new ConcurrentHashMap<>();

    public PromptLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String text(String name) {
        return textCache.computeIfAbsent(name, n -> read(BASE_PATH + n + ".txt"));
    }

    /** 프롬프트의 {{변수}}를 채운다. 값이 없는 변수가 남아 있으면 예외 (빈 프롬프트가 LLM에 가는 것을 방지) */
    public String render(String name, Map<String, ?> variables) {
        Matcher matcher = PLACEHOLDER.matcher(text(name));
        StringBuilder result = new StringBuilder();
        Set<String> missing = new HashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = variables.get(key);
            if (value == null) {
                missing.add(key);
                continue;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value.toString()));
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("프롬프트 " + name + "에 값이 없는 변수: " + missing);
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public JsonNode schema(String name) {
        return schemaCache.computeIfAbsent(name, n -> {
            JsonNode schema = objectMapper.readTree(read(BASE_PATH + n + ".schema.json"));
            List<String> violations = new ArrayList<>();
            checkStrict(schema, "$", violations);
            if (!violations.isEmpty()) {
                throw new IllegalStateException("스키마 " + n + "가 strict 모드 규칙을 지키지 않음: " + violations);
            }
            return schema;
        });
    }

    /**
     * OpenAI strict 모드 규칙: 루트는 object, 모든 object는 additionalProperties=false,
     * 모든 property는 required에 포함 (선택 값은 "type": ["string", "null"]로 표현)
     */
    static void checkStrict(JsonNode node, String path, List<String> violations) {
        if (path.equals("$") && !isType(node, "object")) {
            violations.add("$: 루트 type은 object여야 함");
        }
        if (isType(node, "object")) {
            if (!node.path("additionalProperties").isBoolean() || node.path("additionalProperties").asBoolean()) {
                violations.add(path + ": additionalProperties=false 필요");
            }
            Set<String> required = new HashSet<>();
            node.path("required").forEach(r -> required.add(r.asString()));
            for (String property : node.path("properties").propertyNames()) {
                if (!required.contains(property)) {
                    violations.add(path + "." + property + ": required에 없음");
                }
                checkStrict(node.path("properties").path(property), path + "." + property, violations);
            }
        }
        if (node.has("items")) {
            checkStrict(node.path("items"), path + "[]", violations);
        }
        for (String combinator : List.of("anyOf", "$defs")) {
            JsonNode children = node.path(combinator);
            if (children.isArray()) {
                for (int i = 0; i < children.size(); i++) {
                    checkStrict(children.get(i), path + "." + combinator + "[" + i + "]", violations);
                }
            } else if (children.isObject()) {
                for (String key : children.propertyNames()) {
                    checkStrict(children.path(key), path + "." + combinator + "." + key, violations);
                }
            }
        }
    }

    private static boolean isType(JsonNode node, String type) {
        JsonNode typeNode = node.path("type");
        if (typeNode.isArray()) {
            for (JsonNode t : typeNode) {
                if (type.equals(t.asString())) {
                    return true;
                }
            }
            return false;
        }
        return type.equals(typeNode.asString(""));
    }

    private static String read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("프롬프트 파일이 없음: resources/" + path);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
