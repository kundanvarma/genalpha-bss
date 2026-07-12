package com.bss.policy.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A compact, dependency-free <a href="https://jsonlogic.com">JSON-logic</a>
 * evaluator — enough of the spec to express product rules (quantity caps,
 * incompatibilities, eligibility) as safe DATA rather than executable code.
 *
 * <p>Rules are pure data: there is no code execution, no reflection, no I/O.
 * The worst a malformed or hostile rule can do is evaluate to a boolean, which
 * is exactly why authoring rules from the console is safe. Unknown operators
 * evaluate to {@code null} (falsy) rather than throwing, so one bad rule can
 * never block the order pipeline.
 *
 * <p>Supported: var, missing, ==, ===, !=, !==, &gt;, &gt;=, &lt;, &lt;=,
 * !, !!, and, or, if/?:, in, cat, +, -, *, /, %, min, max, some, all, none.
 */
public final class JsonLogic {

    private JsonLogic() {
    }

    /** Evaluate {@code logic} against {@code data}; returns the raw result. */
    @SuppressWarnings("unchecked")
    public static Object apply(Object logic, Object data) {
        if (!(logic instanceof Map)) {
            // primitives and plain arrays are literals
            if (logic instanceof List<?> list) {
                List<Object> out = new ArrayList<>(list.size());
                for (Object o : list) {
                    out.add(apply(o, data));
                }
                return out;
            }
            return logic;
        }
        Map<String, Object> map = (Map<String, Object>) logic;
        if (map.size() != 1) {
            return logic; // not an operation
        }
        Map.Entry<String, Object> e = map.entrySet().iterator().next();
        String op = e.getKey();
        List<Object> args = e.getValue() instanceof List
                ? new ArrayList<>((List<Object>) e.getValue())
                : new ArrayList<>(List.of(e.getValue() == null ? nullArg() : e.getValue()));

        switch (op) {
            case "var":
                return var(args, data);
            case "missing":
                return missing(args, data);
            case "and": {
                Object last = Boolean.TRUE;
                for (Object a : args) {
                    last = apply(a, data);
                    if (!truthy(last)) {
                        return last;
                    }
                }
                return last;
            }
            case "or": {
                Object last = Boolean.FALSE;
                for (Object a : args) {
                    last = apply(a, data);
                    if (truthy(last)) {
                        return last;
                    }
                }
                return last;
            }
            case "!":
                return !truthy(apply(arg(args, 0), data));
            case "!!":
                return truthy(apply(arg(args, 0), data));
            case "if":
            case "?:": {
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    if (truthy(apply(args.get(i), data))) {
                        return apply(args.get(i + 1), data);
                    }
                }
                return args.size() % 2 == 1 ? apply(args.get(args.size() - 1), data) : null;
            }
            case "==":
                return looseEquals(apply(arg(args, 0), data), apply(arg(args, 1), data));
            case "!=":
                return !looseEquals(apply(arg(args, 0), data), apply(arg(args, 1), data));
            case "===":
                return strictEquals(apply(arg(args, 0), data), apply(arg(args, 1), data));
            case "!==":
                return !strictEquals(apply(arg(args, 0), data), apply(arg(args, 1), data));
            case ">": {
                Integer c = compare(apply(arg(args, 0), data), apply(arg(args, 1), data));
                return c != null && c > 0;
            }
            case ">=": {
                Integer c = compare(apply(arg(args, 0), data), apply(arg(args, 1), data));
                return c != null && c >= 0;
            }
            case "<": {
                Integer c = compare(apply(arg(args, 0), data), apply(arg(args, 1), data));
                return c != null && c < 0;
            }
            case "<=": {
                Integer c = compare(apply(arg(args, 0), data), apply(arg(args, 1), data));
                return c != null && c <= 0;
            }
            case "in":
                return in(apply(arg(args, 0), data), apply(arg(args, 1), data));
            case "cat": {
                StringBuilder sb = new StringBuilder();
                for (Object a : args) {
                    sb.append(str(apply(a, data)));
                }
                return sb.toString();
            }
            case "+":
                return reduce(args, data, 0d, Double::sum);
            case "*":
                return reduce(args, data, 1d, (x, y) -> x * y);
            case "-": {
                if (args.isEmpty()) {
                    return 0d;
                }
                double first = num(apply(args.get(0), data));
                if (args.size() == 1) {
                    return -first;
                }
                return first - num(apply(args.get(1), data));
            }
            case "/":
                return num(apply(arg(args, 0), data)) / num(apply(arg(args, 1), data));
            case "%":
                return num(apply(arg(args, 0), data)) % num(apply(arg(args, 1), data));
            case "min":
                return args.stream().mapToDouble(a -> num(apply(a, data))).min().orElse(0d);
            case "max":
                return args.stream().mapToDouble(a -> num(apply(a, data))).max().orElse(0d);
            case "some":
                return arrayTest(args, data, Mode.SOME);
            case "all":
                return arrayTest(args, data, Mode.ALL);
            case "none":
                return arrayTest(args, data, Mode.NONE);
            default:
                // unknown operator: fail safe (falsy), never throw
                return null;
        }
    }

    /** Convenience: evaluate to a boolean under JSON-logic truthiness. */
    public static boolean test(Object logic, Object data) {
        return truthy(apply(logic, data));
    }

    private enum Mode { SOME, ALL, NONE }

    @SuppressWarnings("unchecked")
    private static Object arrayTest(List<Object> args, Object data, Mode mode) {
        Object collection = apply(arg(args, 0), data);
        Object test = arg(args, 1);
        if (!(collection instanceof List)) {
            return mode == Mode.NONE;
        }
        List<Object> items = (List<Object>) collection;
        int hits = 0;
        for (Object item : items) {
            if (truthy(apply(test, item))) {
                hits++;
            }
        }
        return switch (mode) {
            case SOME -> hits > 0;
            case ALL -> hits == items.size() && !items.isEmpty();
            case NONE -> hits == 0;
        };
    }

    @SuppressWarnings("unchecked")
    private static Object var(List<Object> args, Object data) {
        Object pathArg = args.isEmpty() ? "" : args.get(0);
        Object fallback = args.size() > 1 ? args.get(1) : null;
        String path = str(pathArg);
        if (path.isEmpty()) {
            return data;
        }
        Object current = data;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> m) {
                current = ((Map<String, Object>) m).get(segment);
            } else if (current instanceof List<?> list) {
                try {
                    current = list.get(Integer.parseInt(segment));
                } catch (NumberFormatException | IndexOutOfBoundsException ex) {
                    return fallback;
                }
            } else {
                return fallback;
            }
            if (current == null) {
                return fallback;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Object missing(List<Object> args, Object data) {
        List<Object> keys = args.size() == 1 && args.get(0) instanceof List
                ? (List<Object>) args.get(0) : args;
        List<Object> absent = new ArrayList<>();
        for (Object key : keys) {
            if (var(List.of(key), data) == null) {
                absent.add(key);
            }
        }
        return absent;
    }

    private static double reduce(List<Object> args, Object data, double seed,
            java.util.function.DoubleBinaryOperator fn) {
        double acc = seed;
        for (Object a : args) {
            acc = fn.applyAsDouble(acc, num(apply(a, data)));
        }
        return acc;
    }

    @SuppressWarnings("unchecked")
    private static boolean in(Object needle, Object haystack) {
        if (haystack instanceof String s) {
            return needle != null && s.contains(str(needle));
        }
        if (haystack instanceof List<?> list) {
            for (Object o : (List<Object>) list) {
                if (looseEquals(needle, o)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- coercion & comparison (JSON-logic / JS-ish semantics, kept minimal) ----

    static boolean truthy(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        if (o instanceof String s) {
            return !s.isEmpty();
        }
        if (o instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (o instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    private static boolean looseEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number || b instanceof Number) {
            Double na = asNum(a);
            Double nb = asNum(b);
            if (na != null && nb != null) {
                return na.doubleValue() == nb.doubleValue();
            }
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return truthy(a) == truthy(b);
        }
        return str(a).equals(str(b));
    }

    private static boolean strictEquals(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a == null || b == null) {
            return a == b;
        }
        return a.getClass().equals(b.getClass()) && a.equals(b);
    }

    /**
     * -1/0/1 for ordered numeric operands, or {@code null} when either side is
     * non-numeric. Callers treat null as "unordered" so &gt;, &gt;=, &lt;, &lt;=
     * all evaluate false — a missing quantity is never "over the cap".
     */
    private static Integer compare(Object a, Object b) {
        Double na = asNum(a);
        Double nb = asNum(b);
        if (na == null || nb == null) {
            return null;
        }
        return Double.compare(na, nb);
    }

    private static double num(Object o) {
        Double n = asNum(o);
        return n == null ? 0d : n;
    }

    private static Double asNum(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof Boolean b) {
            return b ? 1d : 0d;
        }
        if (o instanceof String s) {
            if (s.isBlank()) {
                return 0d;
            }
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())
                && !Double.isInfinite(n.doubleValue())) {
            return Long.toString(n.longValue());
        }
        return String.valueOf(o);
    }

    private static Object arg(List<Object> args, int i) {
        return i < args.size() ? args.get(i) : null;
    }

    private static Object nullArg() {
        return null;
    }
}
