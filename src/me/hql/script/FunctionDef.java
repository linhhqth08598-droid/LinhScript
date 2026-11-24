package me.hql.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FunctionDef {

    public static class Param {
        public final String name;
        public final List<Token> defaultTokens; // null nếu không có giá trị mặc định
        public final boolean isRest;           // true nếu là ...args

        public Param(String name, List<Token> defaultTokens, boolean isRest) {
            this.name = name;
            this.defaultTokens = defaultTokens == null ? null : new ArrayList<>(defaultTokens);
            this.isRest = isRest;
        }
    }

    public final String name;
    public final List<String> params;     // giữ tương thích ngược (tên tham số)
    public final List<Param> paramDefs;   // cấu hình chi tiết từng tham số
    public final List<Token> body;
    public final int requiredCount;       // số đối số tối thiểu (không tính tham số có default / rest)
    public final boolean hasRest;

    public FunctionDef(String name, List<String> params, List<Token> body) {
        this(name, toDefaultParams(params), body);
    }

    public FunctionDef(String name, List<Param> paramDefs, List<Token> body) {
        this.name = name;
        this.paramDefs = new ArrayList<>(paramDefs);
        this.body = new ArrayList<>(body);

        List<String> names = new ArrayList<>(paramDefs.size());
        int req = 0;
        boolean rest = false;
        for (Param p : paramDefs) {
            names.add(p.name);
            if (p.isRest) {
                rest = true;
            } else if (p.defaultTokens == null) {
                req++;
            }
        }
        this.params = Collections.unmodifiableList(names);
        this.requiredCount = req;
        this.hasRest = rest;
    }

    private static List<Param> toDefaultParams(List<String> rawNames) {
        List<Param> list = new ArrayList<>();
        if (rawNames == null) return list;
        for (String n : rawNames) {
            if (n != null && n.startsWith("...")) {
                list.add(new Param(n.substring(3), null, true));
            } else {
                list.add(new Param(n, null, false));
            }
        }
        return list;
    }
}