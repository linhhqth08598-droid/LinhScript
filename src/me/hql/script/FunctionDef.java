package me.hql.script;

import java.util.ArrayList;
import java.util.List;

public class FunctionDef {
    public final String name;
    public final List<String> params;
    public final List<Token> body;

    public FunctionDef(String name, List<String> params, List<Token> body) {
        this.name = name;
        this.params = new ArrayList<>(params);
        this.body = new ArrayList<>(body);
    }
}
