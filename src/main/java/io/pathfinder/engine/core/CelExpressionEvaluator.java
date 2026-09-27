package io.pathfinder.engine.core;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CelExpressionEvaluator {
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private final CelCompiler compiler;
    private final CelRuntime runtime;
    private final Map<String, CelRuntime.Program> programCache = new ConcurrentHashMap<>();

    public CelExpressionEvaluator() {
        this.compiler = CelCompilerFactory.standardCelCompilerBuilder()
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .addVar("context", SimpleType.DYN)
                .addVar("results", SimpleType.DYN)
                .addVar("input", SimpleType.DYN)
                .addVar("event", SimpleType.DYN)
                .build();
        this.runtime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
    }

    public boolean evaluateCondition(String condition, Map<String, Object> bindings) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        Object result = evaluate(condition, bindings);
        if (result instanceof Boolean b) {
            return b;
        }
        return false;
    }

    public Object evaluate(String expression, Map<String, Object> bindings) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            CelRuntime.Program program = programCache.computeIfAbsent(expression, expr -> {
                try {
                    CelAbstractSyntaxTree ast = compiler.compile(expr).getAst();
                    return runtime.createProgram(ast);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to compile CEL expression: " + expr, e);
                }
            });

            Map<String, Object> safeBindings = new HashMap<>();
            safeBindings.put("context", bindings.getOrDefault("context", Collections.emptyMap()));
            safeBindings.put("results", bindings.getOrDefault("results", Collections.emptyMap()));
            safeBindings.put("input", bindings.getOrDefault("input", Collections.emptyMap()));
            safeBindings.put("event", bindings.getOrDefault("event", Collections.emptyMap()));

            return program.eval(safeBindings);
        } catch (Exception e) {
            throw new RuntimeException("Error evaluating CEL expression [" + expression + "]: " + e.getMessage(), e);
        }
    }

    public Object resolveTemplateValue(Object value, Map<String, Object> bindings) {
        if (value instanceof String str) {
            Matcher matcher = TEMPLATE_PATTERN.matcher(str);
            if (matcher.matches()) {
                // Whole string is a single expression, return typed result
                String expr = matcher.group(1).trim();
                return evaluate(expr, bindings);
            } else if (matcher.find()) {
                // String contains embedded expressions, interpolate as string
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
            Map<String, Object> resolved = new HashMap<>();
            map.forEach((k, v) -> resolved.put(k.toString(), resolveTemplateValue(v, bindings)));
            return resolved;
        }
        return value;
    }
}
