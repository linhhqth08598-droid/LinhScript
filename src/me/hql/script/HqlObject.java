package me.hql.script;

import java.util.HashMap;

public class HqlObject {
    public HqlClass hqlClass;
    public HashMap<String, Object> fields = new HashMap<>();

    public HqlObject(HqlClass hqlClass) {
        this.hqlClass = hqlClass;
        // Initialize fields from the class definition
        for (String fieldName : hqlClass.fields) {
            fields.put(fieldName, null);
        }
    }
}
