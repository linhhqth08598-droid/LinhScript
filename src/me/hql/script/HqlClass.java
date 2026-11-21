package me.hql.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class HqlClass {
    public String name;
    public String superclassName;
    public HashMap<String, FunctionDef> methods = new HashMap<>();
    public List<String> fields = new ArrayList<>();

    public HqlClass(String name, String superclassName) {
        this.name = name;
        this.superclassName = superclassName;
    }
}
