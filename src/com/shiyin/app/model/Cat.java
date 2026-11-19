package com.shiyin.app.model;

import java.util.ArrayList;
import java.util.List;

/** 分类节点。根节点带 children，子节点没有。 */
public class Cat {
    public String id = "";
    public String name = "";
    public String icon = "";
    public String desc = "";
    public int color = 0xFFF5A524;
    public String source = "";
    public List<Cat> children = new ArrayList<>();

    public Cat() {
    }

    public Cat(String source, String id, String name) {
        this.source = source;
        this.id = id;
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
