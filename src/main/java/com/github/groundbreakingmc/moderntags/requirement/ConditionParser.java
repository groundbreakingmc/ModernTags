package com.github.groundbreakingmc.moderntags.requirement;

import com.github.groundbreakingmc.moderntags.requirement.impl.basic.*;
import com.github.groundbreakingmc.moderntags.requirement.impl.logic.AndCondition;
import com.github.groundbreakingmc.moderntags.requirement.impl.logic.NotCondition;
import com.github.groundbreakingmc.moderntags.requirement.impl.logic.OrCondition;
import com.github.groundbreakingmc.moderntags.util.ValueProvider;
import com.github.retrooper.packetevents.PacketEvents;
import com.google.common.collect.ImmutableMap;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class ConditionParser {

    private static final Map<String, FunctionDefinition> FUNCTIONS;
    private static final Map<String, ValueProvider<?>> VARIABLES;

    private final String rawCondition;
    private final char[] chars;
    private int pos;

    private ConditionParser(String rawCondition) {
        this.rawCondition = rawCondition;
        this.chars = rawCondition.toCharArray();
        this.pos = 0;
    }

    public static Condition parse(@NotNull String rawCondition) {
        Objects.requireNonNull(rawCondition, "rawCondition can't be null");
        return new ConditionParser(rawCondition.trim()).parse();
    }

    public Condition parse() {
        this.skipWhitespace();
        final Condition condition = this.parseOr();
        if (this.pos < this.chars.length) {
            throw new IllegalArgumentException("Unexpected characters '" + this.collectRest() + "' at position " + this.pos + " in expression: " + this.rawCondition);
        }
        return condition;
    }

    private Condition parseOr() {
        Condition left = this.parseAnd();

        while (this.match("||")) {
            skipWhitespace();
            final Condition right = parseAnd();
            left = new OrCondition(left, right);
        }

        return left;
    }

    private Condition parseAnd() {
        Condition left = this.parseUnary();

        while (this.match("&&")) {
            this.skipWhitespace();
            final Condition right = this.parseUnary();
            left = new AndCondition(left, right);
        }

        return left;
    }

    private Condition parseUnary() {
        this.skipWhitespace();

        if (this.match("!")) {
            this.skipWhitespace();
            return new NotCondition(this.parseUnary());
        }

        return this.parsePrimary();
    }

    private Condition parsePrimary() {
        this.skipWhitespace();

        if (this.match("(")) {
            this.skipWhitespace();
            final Condition condition = this.parseOr();
            this.skipWhitespace();
            this.expect(")");
            return condition;
        }

        return this.parseConditionOrComparison();
    }

    private Condition parseConditionOrComparison() {
        final ValueProvider<?> left = this.parseExpression();
        this.skipWhitespace();

        if (this.match("==")) {
            this.skipWhitespace();
            final ValueProvider<?> right = this.parseExpression();
            return new EqualsCondition(left, right);
        }

        if (this.match("!=")) {
            this.skipWhitespace();
            final ValueProvider<?> right = this.parseExpression();
            return new NotEqualsCondition(left, right);
        }

        if (this.match(">=")) {
            this.skipWhitespace();
            final ValueProvider<?> right = this.parseExpression();
            return new GreaterThanOrEqualCondition(left, right);
        }

        if (this.match("<=")) {
            this.skipWhitespace();
            final ValueProvider<?> right = this.parseExpression();
            return new LessThanOrEqualCondition(left, right);
        }

        if (this.match(">")) {
            this.skipWhitespace();
            final ValueProvider<?> right = this.parseExpression();
            return new GreaterThanCondition(left, right);
        }

        if (this.match("<")) {
            this.skipWhitespace();
            final ValueProvider<?> right = this.parseExpression();
            return new LessThanCondition(left, right);
        }

        return new BooleanCondition(left);
    }

    private ValueProvider<?> parseExpression() {
        this.skipWhitespace();

        final char peek = this.peek();

        if (peek == '"' || peek == '\'') {
            final String str = this.parseString();
            return ctx -> str;
        }

        if (peek == '-' || Character.isDigit(peek)) {
            final Number num = this.parseNumber();
            return ctx -> num;
        }

        if (peek == '%') {
            this.consume();
            final String placeholder = '%' + this.parseIdentifier() + '%';
            this.expect("%");
            final int i = placeholder.indexOf('_');
            final String identifier = i != -1
                    ? placeholder.substring(0, i)
                    : placeholder;
            return ctx -> {
                final PlaceholderExpansion expansion = PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion(identifier);
                return expansion != null ? expansion.onRequest(ctx.player(), placeholder) : "no expansion found for placeholder: %" + placeholder + "%";
            };
        }

        boolean variable = false;
        if (this.peek() == '{') {
            variable = true;
            this.consume();
        }

        final String identifier = this.parseIdentifier();
        this.skipWhitespace();

        if (!variable) {
            return this.parseFunctionCall(identifier);
        }

        return this.parseVariable(identifier);
    }

    private ValueProvider<?> parseFunctionCall(String functionName) {
        this.expect("(");

        final List<ValueProvider<?>> arguments = new ArrayList<>();
        this.skipWhitespace();

        if (this.peek() != ')') {
            while (true) {
                arguments.add(this.parseExpression());
                this.skipWhitespace();

                if (this.peek() == ')') {
                    break;
                }

                this.expect(",");
                this.skipWhitespace();
            }
        }

        this.expect(")");

        final var functionDef = FUNCTIONS.get(functionName);
        if (functionDef == null) {
            throw new IllegalArgumentException("Unknown function: '" + functionName + "' in expression: " + this.rawCondition);
        }

        return functionDef.create(arguments);
    }

    private ValueProvider<?> parseVariable(String identifier) {
        this.expect("}");

        final var variableDef = VARIABLES.get(identifier);
        if (variableDef != null) {
            return variableDef;
        }

        throw new IllegalArgumentException("Unknown identifier: '" + identifier + "' in expression: " + this.rawCondition);
    }

    private String parseString() {
        final char quote = this.consume();
        final StringBuilder sb = new StringBuilder();

        while (this.pos < this.chars.length && this.peek() != quote) {
            if (this.peek() == '\\') {
                this.consume();
                if (this.pos < this.chars.length) {
                    final char escaped = this.consume();
                    switch (escaped) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '\\' -> sb.append('\\');
                        case '\'' -> sb.append('\'');
                        case '"' -> sb.append('"');
                        default -> sb.append(escaped);
                    }
                }
            } else {
                sb.append(this.consume());
            }
        }

        this.expect(String.valueOf(quote));
        return sb.toString();
    }

    private Number parseNumber() {
        final int start = this.pos;

        if (this.peek() == '-') {
            this.consume();
        }

        boolean hasDecimal = false;
        while (this.pos < chars.length) {
            final char c = this.peek();
            if (Character.isDigit(c)) {
                this.consume();
            } else if (c == '.' && !hasDecimal) {
                hasDecimal = true;
                this.consume();
            } else {
                break;
            }
        }

        final String numStr = this.rawCondition.substring(start, this.pos);

        if (numStr.equals("-") || numStr.isEmpty()) {
            throw new IllegalArgumentException("Invalid number at position " + start + " in expression: " + this.rawCondition);
        }

        if (hasDecimal) {
            return Double.parseDouble(numStr);
        } else {
            try {
                return Integer.parseInt(numStr);
            } catch (NumberFormatException e) {
                return Long.parseLong(numStr);
            }
        }
    }

    private String parseIdentifier() {
        final int start = this.pos;

        while (this.pos < this.chars.length) {
            final char c = this.peek();
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                this.consume();
            } else {
                break;
            }
        }

        if (start == this.pos) {
            throw new IllegalArgumentException("Expected identifier at position " + this.pos + " in expression: " + this.rawCondition);
        }

        return this.rawCondition.substring(start, this.pos);
    }

    private void skipWhitespace() {
        while (this.pos < this.chars.length && Character.isWhitespace(this.chars[this.pos])) {
            this.pos++;
        }
    }

    private boolean match(String str) {
        final char[] charArray = str.toCharArray();
        final int savePos = this.pos;

        for (int i = 0; i < charArray.length; i++) {
            if (this.pos >= this.chars.length || this.chars[this.pos] != charArray[i]) {
                this.pos = savePos;
                return false;
            }
            this.pos++;
        }

        return true;
    }

    private void expect(String str) {
        if (!this.match(str)) {
            throw new IllegalArgumentException("Expected '" + str + "' at position " + this.pos + " in expression: " + this.rawCondition);
        }
    }

    private char peek() {
        if (this.pos >= this.chars.length)
            throw new IllegalArgumentException("Unexpected end of input at position " + this.pos + " in expression: " + this.rawCondition);
        return this.chars[this.pos];
    }

    private char consume() {
        return this.chars[this.pos++];
    }

    private String collectRest() {
        final StringBuilder restBuilder = new StringBuilder();
        for (int i = this.pos; i < this.chars.length; i++) {
            restBuilder.append(this.chars[i]);
        }
        return restBuilder.toString();
    }

    static {
        FUNCTIONS = ImmutableMap.copyOf(new HashMap<>() {{
            put("hasPermission", args -> {
                final String permission = (String) args.get(0).value(null);
                Objects.requireNonNull(permission, "permission can't be null!");
                return ctx -> ctx.player().hasPermission(permission);
            });
        }});

        VARIABLES = ImmutableMap.copyOf(new HashMap<>() {{
            put("protocol_version", ctx -> PacketEvents.getAPI().getPlayerManager().getClientVersion(ctx.player()).getProtocolVersion());
        }});
    }
}
