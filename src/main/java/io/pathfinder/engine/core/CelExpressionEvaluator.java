package io.pathfinder.engine.core;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CelExpressionEvaluator {
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final int MAX_CACHE_ENTRIES = 1000;

    private final CelCompiler compiler;
    private final CelRuntime runtime;
    private final Map<String, CelRuntime.Program> programCache = new ConcurrentHashMap<>();

    public CelExpressionEvaluator() {
        this.compiler = CelCompilerFactory.standardCelCompilerBuilder()
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .addVar("context", SimpleType.DYN)
                .addVar("data", SimpleType.DYN)
                .addVar("config", SimpleType.DYN)
                .addVar("results", SimpleType.DYN)
                .addVar("input", SimpleType.DYN)
                .addVar("event", SimpleType.DYN)
                .addVar("subflow", SimpleType.DYN)
                .addVar("outcome", SimpleType.DYN)
                .build();
        this.runtime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
    }

    public boolean evaluateCondition(String condition, Map<String, Object> bindings) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        try {
            Object result = evaluate(condition, bindings);
            if (result instanceof Boolean b) {
                return b;
            }
            return false;
        } catch (Exception e) {
            // Unresolved variables or evaluation errors evaluate gracefully to false
            return false;
        }
    }

    public Object evaluate(String expression, Map<String, Object> bindings) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            if (programCache.size() >= MAX_CACHE_ENTRIES) {
                programCache.clear();
            }

            CelRuntime.Program program = programCache.computeIfAbsent(expression, expr -> {
                try {
                    CelAbstractSyntaxTree ast = compiler.compile(expr).getAst();
                    return runtime.createProgram(ast);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to compile CEL expression: " + expr, e);
                }
            });

            Map<String, Object> safeBindings = new HashMap<>();
            safeBindings.put("context", normalizeNumbers(bindings.getOrDefault("context", bindings.getOrDefault("data", Collections.emptyMap()))));
            safeBindings.put("data", normalizeNumbers(bindings.getOrDefault("data", bindings.getOrDefault("context", Collections.emptyMap()))));
            safeBindings.put("config", normalizeNumbers(bindings.getOrDefault("config", Collections.emptyMap())));
            safeBindings.put("results", normalizeNumbers(bindings.getOrDefault("results", Collections.emptyMap())));
            safeBindings.put("input", normalizeNumbers(bindings.getOrDefault("input", Collections.emptyMap())));
            safeBindings.put("event", normalizeNumbers(bindings.getOrDefault("event", Collections.emptyMap())));
            safeBindings.put("subflow", normalizeNumbers(bindings.getOrDefault("subflow", Collections.emptyMap())));
            safeBindings.put("outcome", bindings.getOrDefault("outcome", ""));

            return program.eval(safeBindings);
        } catch (Exception e) {
            throw new RuntimeException("Error evaluating CEL expression [" + expression + "]: " + e.getMessage(), e);
        }
    }

    private Object normalizeNumbers(Object val) {
        if (val instanceof Integer i) {
            return Long.valueOf(i);
        } else if (val instanceof Short s) {
            return Long.valueOf(s);
        } else if (val instanceof Byte b) {
            return Long.valueOf(b);
        } else if (val instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), normalizeNumbers(v)));
            return copy;
        } else if (val instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(normalizeNumbers(item));
            }
            return copy;
        }
        return val;
    }

    public Object resolveTemplateValue(Object value, Map<String, Object> bindings) {
        if (value instanceof String str) {
            Matcher matcher = TEMPLATE_PATTERN.matcher(str);
            if (matcher.matches()) {
                String expr = matcher.group(1).trim();
                return evaluate(expr, bindings);
            } else if (matcher.find()) {
                matcher.reset();
                StringBuilder sb = new StringBuilder();
                while (matcher.find()) {
                    String expr = matcher.group(1).trim();
                    Object eval = evaluate(expr, bindings);
                    matcher.appendReplacement(sb, eval != null ? Matcher.quoteReplacement(eval.toString()) : "");
                }
                matcher.appendTail(sb);
                return sb.toString();
            }
            return str;
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(String.valueOf(k), resolveTemplateValue(v, bindings)));
            return resolved;
        } else if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolveTemplateValue(item, bindings));
            }
            return resolved;
        }
        return value;
    }

    public Map<String, Object> resolveTemplateMap(Map<String, Object> map, Map<String, Object> bindings) {
        if (map == null || map.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            resolved.put(entry.getKey(), resolveTemplateValue(entry.getValue(), bindings));
        }
        return resolved;
    }
}
