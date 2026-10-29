package com.example.unlimitedpossibilities12.messenger.language;

public class Language {
    public String name;
    public String code;
    public String flag;

    public Language(String name, String code, String flag) {
        this.name = name;
        this.code = code;
        this.flag = flag;
    }

    @Override
    public String toString() {
        return flag + " " + name;
    }
}
