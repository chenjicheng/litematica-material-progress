package com.chenjicheng.lmp;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.chenjicheng.lmp.region.MaterialRegion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言文件的完整性测试。
 *
 * <p>漏翻译、两种语言键不对齐、占位符个数对不上，这三类问题都不会让代码编译失败，也不会被任何
 * 行为测试发现——玩家看到的是「界面上一行英文」或「%s 原样印出来」。所以在这里用静态检查钉住。
 *
 * <p>它只做三件事：两种语言的键集合相同、代码里出现的键都在语言文件里、同一键的 {@code %s}
 * 个数在两种语言里一致。
 *
 * <p>第二件事要扫源码，因此依赖 Gradle 的测试工作目录（仓库根目录）。这是本仓库内部的检查，
 * 不是发布产物的一部分。
 */
class LangFilesTest
{
    private static final List<String> LANGUAGES = List.of("zh_cn", "en_us");

    /** 源码里出现的字面量键：以 {@code lmp.} 开头、不以点结尾（拼接用的前缀会以点结尾）。 */
    private static final Pattern LITERAL_KEY = Pattern.compile("\"(lmp\\.[a-z0-9_.]*[a-z0-9_])\"");

    private static final Pattern PLACEHOLDER = Pattern.compile("%s");

    private static Map<String, String> load(String language) throws Exception
    {
        try (InputStream stream = LangFilesTest.class.getResourceAsStream("/assets/lmp/lang/" + language + ".json"))
        {
            assertTrue(stream != null, "找不到语言文件：" + language);

            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, String> entries = new TreeMap<>();

            for (String key : root.keySet())
            {
                entries.put(key, root.get(key).getAsString());
            }

            return entries;
        }
    }

    @Test
    void bothLanguagesDefineTheSameKeys() throws Exception
    {
        Map<String, String> zh = load("zh_cn");
        Map<String, String> en = load("en_us");

        assertEquals(new TreeSet<>(zh.keySet()), new TreeSet<>(en.keySet()),
                "zh_cn 与 en_us 的键必须完全一致");
        assertTrue(zh.size() > 50, "语言文件不该只剩几个键，实际 " + zh.size());
    }

    @Test
    void everyKeyUsedInSourceExistsInBothLanguages() throws Exception
    {
        Set<String> used = new LinkedHashSet<>();

        try (Stream<Path> files = Files.walk(Path.of("src/main/java")))
        {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList())
            {
                Matcher matcher = LITERAL_KEY.matcher(Files.readString(file, StandardCharsets.UTF_8));

                while (matcher.find())
                {
                    used.add(matcher.group(1));
                }
            }
        }

        assertTrue(used.size() > 20, "应该能扫到一批键，实际只扫到 " + used.size());

        for (String language : LANGUAGES)
        {
            Set<String> defined = load(language).keySet();
            Set<String> missing = new TreeSet<>(used);
            missing.removeAll(defined);

            assertEquals(Set.of(), missing, language + " 缺少这些被代码引用的键");
        }
    }

    @Test
    void dynamicKeysExistToo() throws Exception
    {
        // 这些键由代码拼出来，字面量扫描看不到它们，因此单独钉住
        Set<String> dynamic = new TreeSet<>();

        for (MaterialRegion.Invalid invalid : MaterialRegion.Invalid.values())
        {
            dynamic.add(invalid.key());
        }

        dynamic.add("lmp.msg.scan.rate_limited");

        for (String language : LANGUAGES)
        {
            Set<String> defined = load(language).keySet();
            Set<String> missing = new TreeSet<>(dynamic);
            missing.removeAll(defined);

            assertEquals(Set.of(), missing, language + " 缺少这些动态拼接的键");
        }
    }

    @Test
    void placeholdersMatchBetweenLanguages() throws Exception
    {
        Map<String, String> zh = load("zh_cn");
        Map<String, String> en = load("en_us");

        for (Map.Entry<String, String> entry : zh.entrySet())
        {
            int expected = countPlaceholders(entry.getValue());
            int actual = countPlaceholders(en.get(entry.getKey()));

            assertEquals(expected, actual, "键 " + entry.getKey() + " 的 %s 个数在两种语言里不一致");
        }
    }

    private static int countPlaceholders(String value)
    {
        Matcher matcher = PLACEHOLDER.matcher(value);
        int count = 0;

        while (matcher.find())
        {
            count++;
        }

        return count;
    }
}
