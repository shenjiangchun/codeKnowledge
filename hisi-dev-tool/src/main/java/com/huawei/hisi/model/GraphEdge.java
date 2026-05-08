package com.huawei.hisi.model;

public class GraphEdge {
    GraphNode from;
    GraphNode to;

    public GraphEdge(GraphNode from, GraphNode to) {
        this.from = from;
        this.to = to;
    }
}