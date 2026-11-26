package com.huawei.hisi.workbench.service.exec;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把架构深度分析里的证据引用解析回证据包里的具体条目。
 *
 * <p>引用是人读的短句（{@code risks[0] 知识图谱↔语义搜索 cyclic}、
 * {@code longMethods SkillServiceImpl.initSkillDefinitions 350行}、{@code com.huawei.hisi.config→model}），
 * 证据包是分章的大对象（项目 / 模块 / 类 / 方法）。这里把证据包摊成条目，
 * 按「条目所在路径上的键名 + 条目内容」与引用里的词做匹配，取最相关的几条——
 * 自动修复的实证阶段据此拿到具体的类、方法和文件路径，而不是只有一句结论。
 */
public final class ArchEvidenceResolver {

    /** 一条命中的证据：在证据包里的位置和内容。 */
    public record Hit(String where, String json) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern INDEX_HINT = Pattern.compile("([A-Za-z_][\\w]*)\\[(\\d+)]");
    private static final Pattern SPLIT = Pattern.compile("[\\s\\[\\]=,，:：→↔/();；（）<>\"']+");
    /** 引用里的虚词与泛称，不参与匹配。 */
    private static final List<String> STOP_WORDS = List.of("cyclic", "但", "共", "个", "项", "条", "处", "等");
    private static final int HIT_MAX_CHARS = 1500;

    private record Entry(String path, String keys, String matchText, String json) {}

    private record Scored(int score, Entry entry) {}

    private ArchEvidenceResolver() {}

    public static List<Hit> resolve(Map<String, Object> pack, String ref, int maxHits, int maxChars) {
        if (pack == null || ref == null || ref.isBlank() || maxHits <= 0 || maxChars <= 0) {
            return List.of();
        }
        List<String> tokens = tokens(ref);
        if (tokens.isEmpty()) {
            return List.of();
        }
        String indexKey = null;
        int indexNo = -1;
        Matcher hint = INDEX_HINT.matcher(ref);
        if (hint.find()) {
            indexKey = normalize(hint.group(1));
            indexNo = Integer.parseInt(hint.group(2));
        }

        List<Scored> scored = new ArrayList<>();
        for (Entry entry : flatten(pack, "")) {
            int score = 0;
            for (String token : tokens) {
                String wanted = normalize(token);
                if (entry.keys().contains(wanted)) {
                    score += 2;
                } else if (entry.matchText().contains(wanted)) {
                    score += 1;
                }
            }
            if (indexKey != null && entry.keys().contains(indexKey) && entry.path().contains("[" + indexNo + "]")) {
                score += 8;
            }
            if (score > 0) {
                scored.add(new Scored(score, entry));
            }
        }
        // 分数高的在前，同分按路径稳定排序
        scored.sort((a, b) -> a.score() != b.score() ? Integer.compare(b.score(), a.score())
                : a.entry().path().compareTo(b.entry().path()));

        List<Hit> hits = new ArrayList<>();
        int used = 0;
        for (Scored candidate : scored) {
            if (hits.size() >= maxHits) {
                break;
            }
            String json = candidate.entry().json();
            if (json.length() > HIT_MAX_CHARS) {
                json = json.substring(0, HIT_MAX_CHARS) + "…";
            }
            // 至少留一条；其余的在总长度内尽量多给
            if (!hits.isEmpty() && used + json.length() > maxChars) {
                continue;
            }
            hits.add(new Hit(candidate.entry().path(), json));
            used += json.length();
        }
        return hits;
    }

    /** 引用拆成的词：去掉泛称，保留类名、方法名、包路径、指标名这些能对上证据的词。带点的词再拆出各段。 */
    static List<String> tokens(String ref) {
        List<String> out = new ArrayList<>();
        for (String raw : SPLIT.split(ref)) {
            String token = raw.trim();
            if (token.length() >= 2 && !STOP_WORDS.contains(normalize(token)) && !out.contains(token)) {
                out.add(token);
            }
            // globalCycles.domainCycles 这种写法：整体对不上时，各段还能分别对上键名和内容（短段如 com 太泛，不要）
            if (token.contains(".")) {
                for (String part : token.split("\\.")) {
                    if (part.length() >= 4 && !out.contains(part)) {
                        out.add(part);
                    }
                }
            }
        }
        return out;
    }

    /** 比对用的小写形式：去掉下划线（证据引用写 package_cycles，证据包里是 packageCycles）。 */
    private static String normalize(String text) {
        return text.replace("_", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 把证据包摊成条目：全是标量的对象整体算一条（类名、方法、文件路径、行数在一起才有用），
     * 列表里的每个元素各自算一条，单独的标量叶子带上键名算一条。
     */
    @SuppressWarnings("unchecked")
    static List<Entry> flatten(Object node, String path) {
        List<Entry> out = new ArrayList<>();
        if (node instanceof Map<?, ?> map && !isFlat(map)) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.addAll(flatten(e.getValue(), path + "/" + e.getKey()));
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                out.addAll(flatten(list.get(i), path + "[" + i + "]"));
            }
        } else {
            if (node == null) {
                return out;
            }
            Object data = node;
            if (!(node instanceof Map)) {
                String key = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                data = new LinkedHashMap<>(Map.of(key, node));
            }
            try {
                String json = MAPPER.writeValueAsString(data);
                out.add(new Entry(path, normalize(path), normalize(json), json));
            } catch (Exception ignored) {
                // 序列化不了的条目直接跳过
            }
        }
        return out;
    }

    private static boolean isFlat(Map<?, ?> map) {
        for (Object value : map.values()) {
            if (value instanceof Map || value instanceof List) {
                return false;
            }
        }
        return true;
    }
}
