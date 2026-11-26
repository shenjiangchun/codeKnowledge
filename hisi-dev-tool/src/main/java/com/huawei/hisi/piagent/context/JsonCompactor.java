package com.huawei.hisi.piagent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * 结构化载荷的保形压缩：结构原样保留，只截短过长的列表与字符串。
 *
 * <p>被截的列表末尾追加 {@code {"_truncated": "+N 条未展示…"}}，模型据此知道这里不完整。
 * 预算按深度加权——越靠近载荷根的列表越重要（根下两层给 4 倍，第三层 2 倍，更深的 1 倍）：
 * 诊断问题清单这类主体内容通常在浅层，而逐类逐方法的明细在深层。
 */
final class JsonCompactor {

    static final String TRUNCATED = "_truncated";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int listBudgetChars;
    private final int stringBudgetChars;

    private int truncatedLists;

    record Result(JsonNode node, int truncatedLists) {
    }

    JsonCompactor(int listBudgetChars, int stringBudgetChars) {
        this.listBudgetChars = listBudgetChars;
        this.stringBudgetChars = stringBudgetChars;
    }

    Result compact(JsonNode node) {
        return new Result(shrink(node, 0), truncatedLists);
    }

    /** 第 {@code depth} 层的列表预算：载荷根的直接子列表记为第 1 层。 */
    static int budgetFor(int depth, int listBudgetChars) {
        int factor = depth <= 2 ? 4 : depth == 3 ? 2 : 1;
        return listBudgetChars * factor;
    }

    private JsonNode shrink(JsonNode node, int depth) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            node.fields().forEachRemaining(field -> out.set(field.getKey(), shrink(field.getValue(), depth + 1)));
            return out;
        }
        if (node.isArray()) {
            return shrinkArray(node, depth);
        }
        if (node.isTextual() && node.textValue().length() > stringBudgetChars) {
            return TextNode.valueOf(node.textValue().substring(0, stringBudgetChars) + "…");
        }
        return node;
    }

    private JsonNode shrinkArray(JsonNode array, int depth) {
        int budget = budgetFor(depth, listBudgetChars);
        ArrayNode out = MAPPER.createArrayNode();
        int used = 0;
        for (JsonNode item : array) {
            JsonNode shrunk = shrink(item, depth + 1);
            int size = shrunk.toString().length();
            // 至少保留一条，让人看得出这一项的结构
            if (!out.isEmpty() && used + size > budget) {
                break;
            }
            out.add(shrunk);
            used += size;
        }
        if (out.size() < array.size()) {
            out.addObject().put(TRUNCATED, "+" + (array.size() - out.size()) + " 条未展示，需要细节时查看完整原文");
            truncatedLists++;
        }
        return out;
    }
}
