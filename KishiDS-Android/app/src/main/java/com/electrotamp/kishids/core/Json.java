package com.electrotamp.kishids.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A very small JSON reader/writer, enough for profile files: one object whose values are numbers, strings or arrays of numbers.
 * It exists so the core stays free of Android classes and can be tested on a plain JVM.
 */
public final class Json {
    private Json() {}

    public static String write(Map<String, Object> profile) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, Object> e : profile.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("  ").append(quote(e.getKey())).append(": ");
            Object v = e.getValue();
            if (v instanceof int[]) {
                int[] a = (int[]) v;
                sb.append("[");
                for (int i = 0; i < a.length; i++) sb.append(i == 0 ? "" : ", ").append(a[i]);
                sb.append("]");
            } else if (v instanceof String) sb.append(quote((String) v));
            else sb.append(v);
        }
        return sb.append("\n}\n").toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** Parse an object into Maps, Lists, Doubles/Integers and Strings.  Throws IllegalArgumentException when it is not JSON. */
    public static Map<String, Object> parseObject(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (!(v instanceof Map) || p.i != text.length()) throw new IllegalArgumentException("not a JSON object");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s.startsWith("﻿") ? s.substring(1) : s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        IllegalArgumentException err() { return new IllegalArgumentException("bad JSON at " + i); }

        Object value() {
            if (i >= s.length()) throw err();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw err();
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err();
                i++;
                ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) throw err();
                if (s.charAt(i) == ',') { i++; continue; }
                if (s.charAt(i) == '}') { i++; return m; }
                throw err();
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) throw err();
                if (s.charAt(i) == ',') { i++; continue; }
                if (s.charAt(i) == ']') { i++; return l; }
                throw err();
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err();
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err();
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            throw err();
        }

        Number number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err();
            String t = s.substring(st, i);
            try {
                return t.contains(".") || t.contains("e") || t.contains("E") ? (Number) Double.valueOf(t) : (Number) Long.valueOf(t);
            } catch (NumberFormatException ex) { throw err(); }
        }
    }
}
