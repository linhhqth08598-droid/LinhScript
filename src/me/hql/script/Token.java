package me.hql.script;

public class Token {
    public String type;
    public String value;
    public String file; // Thêm tên file
    public int line;    // Thêm số dòng

    public Token(String type, String value, String file, int line) {
        this.type = type;
        this.value = value;
        this.file = file;
        this.line = line;
    }

    @Override
    public String toString() {
        return "[" + type + ": " + value + " (line " + line + ")]";
    }
}