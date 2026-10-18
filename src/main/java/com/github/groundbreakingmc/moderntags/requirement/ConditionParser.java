package com.github.groundbreakingmc.moderntags.requirement;

import com.github.groundbreakingmc.moderntags.requirement.impl.basic.*;
import com.github.groundbreakingmc.moderntags.requirement.impl.logic.AndCondition;
import com.github.groundbreakingmc.moderntags.requirement.impl.logic.NotCondition;
import com.github.groundbreakingmc.moderntags.requirement.impl.logic.OrCondition;
import com.github.groundbreakingmc.moderntags.util.ValueProvider;
import com.github.retrooper.packetevents.PacketEvents;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ConditionParser {

    private static final Map<String, FunctionDefinition> FUNCTIONS = Map.of(
            "hasPermission", arguments -> {
                final var permission = (String) arguments.get(0).value(null);
                Objects.requireNonNull(permission, "permission can't be null!");
                return context -> context.player().hasPermission(permission);
            }
    );
    private static final Map<String, ValueProvider<?>> VARIABLES = Map.of(
            "protocol_version", context -> PacketEvents.getAPI().getPlayerManager()
                    .getClientVersion(context.player()).getProtocolVersion()
    );

    private final String rawCondition;
    private final char[] chars;
    private int pos;

    private ConditionParser(String rawCondition) {
        this.rawCondition = rawCondition;
        this.chars = rawCondition.toCharArray();
    }

    public static Condition parse(@NotNull String rawCondition) {
        Objects.requireNonNull(rawCondition, "rawCondition can't be null");
        return new ConditionParser(rawCondition.trim()).parse();
    }

    public Condition parse() {
        this.skipWhitespace();
        final var condition = this.parseOr();
        if (this.pos < this.chars.length) {
            throw new IllegalArgumentException("Unexpected characters '" + this.rawCondition.substring(this.pos)
                    + "' at position " + this.pos + " in expression: " + this.rawCondition);
        }
        return condition;
    }

    private Condition parseOr() {
        Condition left = this.parseAnd();

        while (true) {
            this.skipWhitespace();
            if (!this.match("||")) return left;

            left = new OrCondition(left, this.parseAnd());
        }
    }

    private Condition parseAnd() {
        Condition left = this.parseUnary();

        while (true) {
            this.skipWhitespace();
            if (!this.match("&&")) return left;

            left = new AndCondition(left, this.parseUnary());
        }
    }

    private Condition parseUnary() {
        this.skipWhitespace();

        if (this.match("!")) {
            return new NotCondition(this.parseUnary());
        }

        return this.parsePrimary();
    }

    private Condition parsePrimary() {
        this.skipWhitespace();

        if (this.match("(")) {
            final var condition = this.parseOr();
            this.skipWhitespace();
            this.expect(")");
            return condition;
        }

        return this.parseConditionOrComparison();
    }

    private Condition parseConditionOrComparison() {
        final var left = this.parseExpression();
        this.skipWhitespace();

        if (this.match("==")) {
            return new EqualsCondition(left, this.parseExpression());
        }

        if (this.match("!=")) {
            return new NotEqualsCondition(left, this.parseExpression());
        }

        if (this.match(">=")) {
            return new GreaterThanOrEqualCondition(left, this.parseExpression());
        }

        if (this.match("<=")) {
            return new LessThanOrEqualCondition(left, this.parseExpression());
        }

        if (this.match(">")) {
            return new GreaterThanCondition(left, this.parseExpression());
        }

        if (this.match("<")) {
            return new LessThanCondition(left, this.parseExpression());
        }

        return new BooleanCondition(left);
    }

    private ValueProvider<?> parseExpression() {
        this.skipWhitespace();

        final char peek = this.peek();

        if (peek == '"' || peek == '\'') {
            final var value = this.parseString();
            return context -> value;
        }

        if (peek == '-' || Character.isDigit(peek)) {
            final var value = this.parseNumber();
            return context -> value;
        }

        if (peek == '%') {
            this.consume();
            final var key = this.parseIdentifier();
            this.expect("%");

            final int sepIdx = key.indexOf('_');
            final var identifier = (sepIdx != -1 ? key.substring(0, sepIdx) : key)
                    .toLowerCase(Locale.ROOT);
            final var params = sepIdx != -1 ? key.substring(sepIdx + 1) : "";

            return context -> {
                // Resolve on evaluation so expansion registration changes remain visible.
                final var expansion = PlaceholderAPIPlugin.getInstance()
                        .getLocalExpansionManager()
                        .getExpansion(identifier);
                return expansion != null
                        ? expansion.onRequest(context.player(), params)
                        : "no expansion found for placeholder: %" + key + "%";
            };
        }

        final boolean variable = peek == '{';
        if (variable) {
            this.consume();
        }

        final var identifier = this.parseIdentifier();
        this.skipWhitespace();

        if (!variable) {
            return this.parseFunctionCall(identifier);
        }

        return this.parseVariable(identifier);
    }

    private ValueProvider<?> parseFunctionCall(String functionName) {
        this.expect("(");

        final var arguments = new ArrayList<ValueProvider<?>>();
        this.skipWhitespace();

        if (this.peek() != ')') {
            while (true) {
                arguments.add(this.parseExpression());
                this.skipWhitespace();

                if (this.peek() == ')') {
                    break;
                }

                this.expect(",");
            }
        }

        this.expect(")");

        final var function = FUNCTIONS.get(functionName);
        if (function == null) {
            throw new IllegalArgumentException("Unknown function: '" + functionName
                    + "' in expression: " + this.rawCondition);
        }

        return function.create(arguments);
    }

    private ValueProvider<?> parseVariable(String identifier) {
        this.expect("}");

        final var variable = VARIABLES.get(identifier);
        if (variable != null) {
            return variable;
        }

        throw new IllegalArgumentException("Unknown identifier: '" + identifier
                + "' in expression: " + this.rawCondition);
    }

    private String parseString() {
        final char quote = this.consume();
        final var sb = new StringBuilder();

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
        while (this.pos < this.chars.length) {
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

        final var numStr = this.rawCondition.substring(start, this.pos);

        if (numStr.equals("-") || numStr.isEmpty()) {
            throw new IllegalArgumentException("Invalid number at position " + start
                    + " in expression: " + this.rawCondition);
        }

        if (hasDecimal) {
            return Double.parseDouble(numStr);
        }
        try {
            return Integer.parseInt(numStr);
        } catch (NumberFormatException ignored) {
            return Long.parseLong(numStr);
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
            throw new IllegalArgumentException("Expected identifier at position " + this.pos
                    + " in expression: " + this.rawCondition);
        }

        return this.rawCondition.substring(start, this.pos);
    }

    private void skipWhitespace() {
        while (this.pos < this.chars.length && Character.isWhitespace(this.chars[this.pos])) {
            this.pos++;
        }
    }

    private boolean match(String token) {
        final int start = this.pos;

        for (int i = 0; i < token.length(); i++) {
            if (this.pos >= this.chars.length || this.chars[this.pos] != token.charAt(i)) {
                this.pos = start;
                return false;
            }
            this.pos++;
        }

        return true;
    }

    private void expect(String token) {
        if (!this.match(token)) {
            throw new IllegalArgumentException("Expected '" + token + "' at position " + this.pos
                    + " in expression: " + this.rawCondition);
        }
    }

    private char peek() {
        if (this.pos >= this.chars.length) {
            throw new IllegalArgumentException("Unexpected end of input at position " + this.pos
                    + " in expression: " + this.rawCondition);
        }
        return this.chars[this.pos];
    }

    private char consume() {
        return this.chars[this.pos++];
    }
}
