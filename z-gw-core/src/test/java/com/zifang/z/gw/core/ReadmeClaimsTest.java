package com.zifang.z.gw.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * z-gw README 定量主张自守卫 —— README 里写死的数字、名单、端口、版本、坐标，
 * 必须能从源码重算出来；README 改了就红，代码没动就不红。
 *
 * <p><b>为什么要有这一层</b>：本仓 README 把「5 个 Maven 子模块」「4 个 reactor 模块」
 * 「z-gw-api 19 个类」「z-gw-core 47 个类」「内置 6 个谓词」「8 个过滤器工厂」
 * 「5 种负载均衡」「3 种限流算法」「z-gw-admin 三个 Controller」「12 个管理端点」
 * 「默认端口 9090 / 8888」「入站读超时 60 秒」「根 DM 5 个坐标钉 ${project.version}」
 * 「26 个测试类 / 185 个 @Test」写成了硬数字。每加一个类、每加一个端点、每抬一次版本、
 * 每改一次默认端口都会漂一次，而没有任何尺子去问代码「现在还是吗」，
 * 这些数字的保质期就只剩上一次编辑 README 的那一刻。</p>
 *
 * <p><b>口径四条（都是踩过的坑）</b>：</p>
 * <ol>
 *   <li><b>事实对象只装源码侧事实</b>（{@link Facts}）—— README 侧的数值一律在核对
 *       <b>当下</b>从被核对的那份 README 里现解析。两边都从同一份 README 取数，
 *       尺子就成了恒等式比较，README 改成 8081 尺子照样绿；</li>
 *   <li><b>主张抠不到就红</b>：正则没匹配上不许静默跳过，那正是空跑；</li>
 *   <li><b>按结构树 / 章节名 / 表格字段定位，不按数字定位</b>：短数字是长数字的子串
 *       （「4 个类」⊂「14 个类」），按数字定位会匹配到别人的行上；</li>
 *   <li><b>README 标题带 emoji</b>：定位小节要剥掉 emoji 后<b>标题文字全等匹配</b>，
 *       不能用 {@code contains}（「测试」会误命中「编译与测试」）。</li>
 * </ol>
 *
 * <p>数值不等 = 红，且把<b>重算值</b>打进消息（红了要知道真数是多少，否则只是又一处猜）。
 * 核对结果<b>一次全报</b>（收集齐所有不一致再抛），撞停会把尺子退化成「一次只说一句」；
 * 尺子的牙口由 {@link #尺子的牙口()} 用合成 README 证明，
 * 而「不许冤枉健康页」由 {@link #不许冤枉健康页()} 本身证明。</p>
 */
class ReadmeClaimsTest {

    /** 本守卫自己的文件名 —— 复算测试规模时按 README 给的口径排除自己。 */
    private static final String GUARD_FILE = "ReadmeClaimsTest.java";

    /** 仓根标志：artifactId 命中 + packaging=pom + 真带 <module>（子模块 pom 的 parent 里也有同名 artifactId）。 */
    private static final String ROOT_ARTIFACT_ID = "z-gw";

    /** 源码侧路径一律相对仓根写死，不按目录名统计（多模块仓里同名目录不止一处）。 */
    private static final String API_MAIN = "z-gw-api/src/main/java";
    private static final String CORE_MAIN = "z-gw-core/src/main/java";
    private static final String CORE_TEST = "z-gw-core/src/test/java";
    private static final String STARTER_TEST = "z-gw-spring-boot-starter/src/test/java";
    private static final String ADMIN_MAIN = "z-gw-admin/src/main/java";
    private static final String CORE_PKG = "com/zifang/z/gw/core";
    private static final String EXAMPLES_YML = "z-gw-examples/src/main/resources/application.yml";

    // ==================================================================
    // 基础设施
    // ==================================================================

    /**
     * 定位仓根：从 user.dir 向上找<b>聚合根</b>的 pom.xml。
     *
     * <p>判据要同时满足三条：artifactId 命中 + {@code <packaging>pom</packaging>} +
     * 至少一个 {@code <module>}。只看 artifactId 会被子模块 pom 的
     * {@code <parent>} 里的同名 artifactId 骗过去（子模块的 parent 就是 {@code z-gw}）。</p>
     */
    private static Path repoRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 10 && p != null; i++, p = p.getParent()) {
            Path pom = p.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                String t = stripXmlComments(readText(pom));
                boolean isRoot = t.contains("<artifactId>" + ROOT_ARTIFACT_ID + "</artifactId>")
                        && t.contains("<packaging>pom</packaging>")
                        && Pattern.compile("<module>").matcher(t).find();
                if (isRoot) {
                    return p;
                }
            }
        }
        throw new IllegalStateException("未找到 z-gw 仓根：从 " + Paths.get("").toAbsolutePath()
                + " 向上找不到同时满足 artifactId=" + ROOT_ARTIFACT_ID + " / packaging=pom / 带 <module> 的 pom.xml");
    }

    private static String readText(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读文件失败：" + p, e);
        }
    }

    /** 剥掉 XML 注释（根 pom 里注释掉的 {@code <module>z-gw-examples</module>} 会骗过正则）。 */
    private static String stripXmlComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    /** README 全文（每次现读 —— README 侧的数值必须在核对当下现解析）。 */
    private static String readme() {
        return readText(repoRoot().resolve("README.md"));
    }

    /**
     * 剥掉注释（块注释 + 行注释），字符串字面量原样保留。
     *
     * <p>判「类级注解」还是「方法级注解」时必须先剥注释：Javadoc 里出现过
     * {@code @RequestMapping} 字样就会被当成真映射。</p>
     */
    private static String stripComments(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                sb.append(c);
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    sb.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        sb.append(s.charAt(i));
                        i++;
                        continue;
                    }
                    if (d == quote) {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // ==================================================================
    // README 侧解析
    // ==================================================================

    private static int headingLevel(String heading) {
        int n = 0;
        while (n < heading.length() && heading.charAt(n) == '#') {
            n++;
        }
        return n;
    }

    /**
     * 去掉行首 #、emoji 与变体选择符，剩下的就是标题文字。
     *
     * <p>emoji 用码点判断（不吃编译编码的亏）：U+1F300–U+1FAFF、U+2600–U+27BF、
     * 变体选择符 U+FE0F、零宽连接符 U+200D。</p>
     */
    private static String headingText(String raw) {
        String t = raw.trim();
        while (t.startsWith("#")) {
            t = t.substring(1);
        }
        t = t.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            int cp = t.codePointAt(i);
            if (cp == 0xFE0F || cp == 0x200D
                    || (cp >= 0x1F300 && cp <= 0x1FAFF)
                    || (cp >= 0x2600 && cp <= 0x27BF)) {
                if (cp > 0xFFFF) {
                    i++;
                }
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString().trim();
    }

    /**
     * 取某个标题的小节正文（到下一个「同级或更高级」标题为止，{@code ###} 子标题仍属本节）。
     *
     * <p>围栏代码块内以 # 开头的行（项目结构树）不算标题，必须跳过。</p>
     */
    private static String section(String readme, String headingText) {
        String[] lines = readme.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        boolean fence = false;
        int level = 99;
        List<String> seen = new ArrayList<String>();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("```")) {
                fence = !fence;
            }
            if (!in) {
                if (!fence && t.startsWith("#")) {
                    String text = headingText(t);
                    seen.add(text);
                    if (text.equals(headingText)) {
                        in = true;
                        level = headingLevel(t);
                    }
                }
                continue;
            }
            if (!fence && t.startsWith("#") && headingLevel(t) <= level) {
                break;
            }
            sb.append(line).append('\n');
        }
        if (!in) {
            fail("README 里没有标题文字等于 \"" + headingText + "\" 的小节；现有标题=" + seen);
        }
        return sb.toString();
    }

    private static String[] splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        return Arrays.stream(t.split("\\|", -1)).map(String::trim).toArray(String[]::new);
    }

    /** 取小节里首格等于 field 的那一行的各格（0 是字段名本身）。 */
    private static List<String> rowCells(String sectionText, String field) {
        for (String line : sectionText.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            String[] cells = splitRow(t);
            if (cells.length > 0 && cells[0].equals(field)) {
                return Arrays.asList(cells);
            }
        }
        return null;
    }

    /** 项目结构树里某个节点的整行（锚点是树形前缀 + 节点名，不是数字）。 */
    private static String treeLine(String readme, String node) {
        // 行首可能有树形竖线「│」前缀（嵌套层的节点行），所以用 ^.* 吃掉前缀
        Matcher m = Pattern.compile("(?m)^.*(?:├──|└──)[ \t]*" + Pattern.quote(node)
                        + "(?:/|(?=[ \\t#]))[^\\n]*$")
                .matcher(readme);
        if (!m.find()) {
            fail("README 的项目结构树里找不到节点行：" + node + "（结构树被改过形？还是这行被挪走了？）");
        }
        return m.group();
    }

    /** 小节里第一个匹配模式的捕获组（抠不到就红，不许静默跳过）。 */
    private static String claim(String text, Pattern p, String what) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            fail("README 主张抠不到：" + what + "（模式 " + p.pattern() + "）");
        }
        return m.group(1);
    }

    /** 抠不出数字就红。 */
    private static int intClaim(String text, Pattern p, String what) {
        return Integer.parseInt(claim(text, p, what));
    }

    /** 文本里所有反引号包裹的片段。 */
    private static List<String> codeSpans(String cell) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(cell);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    /** 中文数字（一…十）→ 阿拉伯数字。README 里「三个 Controller」这类写法要能读。 */
    private static int chineseNum(String s) {
        Map<Character, Integer> digits = new LinkedHashMap<Character, Integer>();
        String chars = "零一二三四五六七八九";
        for (int i = 0; i < chars.length(); i++) {
            digits.put(chars.charAt(i), i);
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return -1;
        }
        int total = 0;
        int section = 0;
        int number = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '十') {
                section += (number == 0 ? 1 : number) * 10;
                number = 0;
            } else if (digits.containsKey(c)) {
                number = digits.get(c);
            } else {
                return -1;
            }
        }
        total = section + number;
        return total;
    }

    // ==================================================================
    // 源码侧事实
    // ==================================================================

    private static Element projectElement(Path pom) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setIgnoringComments(true);
            Document doc = f.newDocumentBuilder().parse(pom.toFile());
            return doc.getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("解析 pom 失败：" + pom, e);
        }
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<Element>();
        if (parent == null) {
            return out;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String text(Element e) {
        return e == null ? "" : e.getTextContent().trim();
    }

    /** 根 pom 的项目自身信息（XML 解析，不走正则：正则会抓到 &lt;parent&gt; 里的同名标签）。 */
    private static Element rootProject() {
        return projectElement(repoRoot().resolve("pom.xml"));
    }

    /** 根 pom 的 {@code <modules>} 声明顺序（注释掉的 module 由 XML 解析天然排除）。 */
    private static List<String> pomModules() {
        Element modules = child(rootProject(), "modules");
        if (modules == null) {
            throw new IllegalStateException("根 pom 里没有 <modules>");
        }
        List<String> out = new ArrayList<String>();
        NodeList nl = modules.getElementsByTagName("module");
        for (int i = 0; i < nl.getLength(); i++) {
            out.add(nl.item(i).getTextContent().trim());
        }
        return out;
    }

    /** 根 pom 的 {@code <properties>}。 */
    private static Map<String, String> rootProperties() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Element props = child(rootProject(), "properties");
        if (props == null) {
            return out;
        }
        NodeList nl = props.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.put(n.getNodeName(), n.getTextContent().trim());
            }
        }
        return out;
    }

    /** 根 pom {@code <dependencyManagement>} 里的条目：[groupId, artifactId, version]。 */
    private static List<String[]> rootManagedDeps() {
        List<String[]> out = new ArrayList<String[]>();
        Element dm = child(child(rootProject(), "dependencyManagement"), "dependencies");
        for (Element d : children(dm, "dependency")) {
            out.add(new String[] {
                    text(child(d, "groupId")), text(child(d, "artifactId")), text(child(d, "version"))});
        }
        return out;
    }

    /** 把 {@code ${xxx}} 属性引用解成字面值。 */
    private static String resolve(String version, Map<String, String> props) {
        if (version == null) {
            return "";
        }
        Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(version);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String v = props.containsKey(key) ? props.get(key) : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 根 pom 的 build/pluginManagement 里某个插件的版本。 */
    private static String managedPluginVersion(String artifactId) {
        Element build = child(rootProject(), "build");
        Element pm = child(build, "pluginManagement");
        for (Element plugins : children(pm, "plugins")) {
            for (Element p : children(plugins, "plugin")) {
                if (artifactId.equals(text(child(p, "artifactId")))) {
                    return text(child(p, "version"));
                }
            }
        }
        return "";
    }

    /** 根 pom build/plugins 里某个插件的版本。 */
    private static String buildPluginVersion(String artifactId) {
        Element build = child(rootProject(), "build");
        for (Element plugins : children(build, "plugins")) {
            for (Element p : children(plugins, "plugin")) {
                if (artifactId.equals(text(child(p, "artifactId")))) {
                    return text(child(p, "version"));
                }
            }
        }
        return "";
    }

    /** central profile 里 central-publishing-maven-plugin 的 excludeArtifacts 名单。 */
    private static Set<String> centralExcludedArtifacts() {
        Set<String> out = new TreeSet<String>();
        Element profiles = child(rootProject(), "profiles");
        for (Element profile : children(profiles, "profile")) {
            if (!"central".equals(text(child(profile, "id")))) {
                continue;
            }
            Element build = child(profile, "build");
            for (Element plugins : children(build, "plugins")) {
                for (Element p : children(plugins, "plugin")) {
                    if (!"central-publishing-maven-plugin".equals(text(child(p, "artifactId")))) {
                        continue;
                    }
                    Element cfg = child(p, "configuration");
                    Element ex = child(cfg, "excludeArtifacts");
                    for (Element a : children(ex, "excludeArtifact")) {
                        out.add(a.getTextContent().trim());
                    }
                }
            }
        }
        return out;
    }

    /** 仓内带 pom.xml 的子模块目录名（z-gw-admin-frontend 没有 pom，不算）。 */
    private static List<String> subModuleNames() {
        List<String> out = new ArrayList<String>();
        for (Path p : listDirs(repoRoot())) {
            String n = p.getFileName().toString();
            if (n.startsWith(ROOT_ARTIFACT_ID + "-") && Files.isRegularFile(p.resolve("pom.xml"))) {
                out.add(n);
            }
        }
        return out;
    }

    private static List<Path> listDirs(Path dir) {
        List<Path> out = new ArrayList<Path>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                if (Files.isDirectory(p)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static List<Path> listFiles(Path dir) {
        List<Path> out = new ArrayList<Path>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 某目录下的 .java 文件（直接子文件）。 */
    private static List<Path> javaFiles(Path dir) {
        List<Path> out = new ArrayList<Path>();
        for (Path p : listFiles(dir)) {
            if (p.getFileName().toString().endsWith(".java")) {
                out.add(p);
            }
        }
        return out;
    }

    /** 全仓 .java 文件（排除 target / node_modules / .git）。 */
    private static List<Path> allJavaFiles() {
        List<Path> out = new ArrayList<Path>();
        try (Stream<Path> s = Files.walk(repoRoot())) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                String n = p.getFileName().toString();
                String path = p.toString();
                if (path.contains("/target/") || path.contains("/node_modules/") || path.contains("/.git/")) {
                    continue;
                }
                if (n.endsWith(".java")) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 某个包目录下（<b>递归</b>）的 .java 文件数。 */
    private static int mainClassCountRecursive(String module, String pkg) {
        Path dir = repoRoot().resolve(module).resolve("src/main/java").resolve(pkg);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("目录不存在：" + dir);
        }
        int n = 0;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                if (p.getFileName().toString().endsWith(".java")) {
                    n++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return n;
    }

    /** 某个包目录下的 .java 文件数（<b>只数直接子文件</b>，不递归）。 */
    private static int mainClassCount(String module, String pkg) {
        Path dir = repoRoot().resolve(module).resolve("src/main/java").resolve(pkg);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("目录不存在：" + dir);
        }
        return javaFiles(dir).size();
    }

    /** 某个目录下的类名（去 .java 后缀）。 */
    private static Set<String> classNames(Path dir, String suffix) {
        Set<String> out = new TreeSet<String>();
        for (Path p : javaFiles(dir)) {
            String n = p.getFileName().toString();
            n = n.substring(0, n.length() - ".java".length());
            if (suffix == null || n.endsWith(suffix)) {
                out.add(n);
            }
        }
        return out;
    }

    /** 某个目录下按名字后缀筛出的类名（去后缀后比较）。 */
    private static Set<String> classNamesWithoutSuffix(Path dir, String suffix) {
        Set<String> out = new TreeSet<String>();
        for (String n : classNames(dir, suffix)) {
            out.add(n.substring(0, n.length() - suffix.length()));
        }
        return out;
    }

    /** 源码里某个常量的字面值。 */
    private static String constant(Path file, String name) {
        Matcher m = Pattern.compile("String\\s+" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"")
                .matcher(stripComments(readText(file)));
        if (!m.find()) {
            throw new IllegalStateException(file + " 里找不到常量 " + name);
        }
        return m.group(1);
    }

    /** 某个源文件里某方法的函数体（按大括号配平）。 */
    private static String methodBody(String src, String signature) {
        int idx = src.indexOf(signature);
        if (idx < 0) {
            throw new IllegalStateException("源码里找不到方法：" + signature);
        }
        int open = src.indexOf('{', idx);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open + 1, i);
                }
            }
        }
        throw new IllegalStateException("方法体大括号不配平：" + signature);
    }

    /** z-gw-admin 的管理端点（方法 + 路径），从 Controller 的注解现读。 */
    private static Set<String> adminEndpoints() {
        Set<String> out = new TreeSet<String>();
        Path dir = repoRoot().resolve(ADMIN_MAIN).resolve("com/zifang/z/gw/admin/controller");
        for (Path p : javaFiles(dir)) {
            String src = stripComments(readText(p));
            int cls = src.indexOf("public class ");
            if (cls < 0) {
                throw new IllegalStateException("源文件里找不到类声明：" + p);
            }
            Matcher cm = Pattern.compile("@RequestMapping\\s*\\(\\s*\"([^\"]*)\"\\s*\\)").matcher(src.substring(0, cls));
            if (!cm.find()) {
                throw new IllegalStateException("Controller 没有类级 @RequestMapping：" + p);
            }
            String base = cm.group(1);
            Matcher mm = Pattern.compile("@(Get|Post|Put|Delete)Mapping\\s*(?:\\(\\s*(?:\"([^\"]*)\"\\s*)?\\))?")
                    .matcher(src.substring(cls));
            while (mm.find()) {
                String method = mm.group(1).toUpperCase();
                String sub = mm.group(2) == null ? "" : mm.group(2);
                out.add(method + " " + base + sub);
            }
        }
        return out;
    }

    /** 过滤器链里注册的全局过滤器（按源码顺序，取类名去 GlobalFilter 后缀）。 */
    private static List<String> globalFilterOrder() {
        Path f = repoRoot().resolve(CORE_MAIN).resolve(CORE_PKG).resolve("filter/FilterChainBootstrap.java");
        Matcher m = Pattern.compile("addGlobalFilter\\s*\\(\\s*new\\s+(\\w+)\\s*\\(")
                .matcher(stripComments(readText(f)));
        List<String> out = new ArrayList<String>();
        while (m.find()) {
            String n = m.group(1);
            out.add(n.endsWith("GlobalFilter") ? n.substring(0, n.length() - "GlobalFilter".length()) : n);
        }
        return out;
    }

    /** installDefaults 里注册的过滤器工厂（按源码顺序）。 */
    private static List<String> registeredFilterFactories() {
        Path f = repoRoot().resolve(CORE_MAIN).resolve(CORE_PKG).resolve("filter/FilterChainBootstrap.java");
        Matcher m = Pattern.compile("factoryRegistry\\.register\\s*\\(\\s*new\\s+(\\w+)\\s*\\(")
                .matcher(stripComments(readText(f)));
        List<String> out = new ArrayList<String>();
        while (m.find()) {
            String n = m.group(1);
            out.add(n.endsWith("FilterFactory") ? n.substring(0, n.length() - "FilterFactory".length()) : n);
        }
        return out;
    }

    /** 三个限流器的算法名（NAME 常量）。 */
    private static Map<String, String> rateLimiterAlgorithms() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Path dir = repoRoot().resolve(CORE_MAIN).resolve(CORE_PKG).resolve("ratelimit");
        for (Path p : javaFiles(dir)) {
            String n = p.getFileName().toString();
            if (!n.endsWith("RateLimiter.java")) {
                continue;
            }
            out.put(n.substring(0, n.length() - ".java".length()), constant(p, "NAME"));
        }
        return out;
    }

    /** 某个源文件里读到的整数常量（形如 {@code private int x = 1234;}）。 */
    private static int intField(Path file, String field) {
        Matcher m = Pattern.compile("private\\s+(?:int|long|short)\\s+" + Pattern.quote(field)
                        + "\\s*=\\s*(\\d+)")
                .matcher(stripComments(readText(file)));
        if (!m.find()) {
            throw new IllegalStateException(file + " 里找不到字段 " + field);
        }
        return Integer.parseInt(m.group(1));
    }

    /** 某个源文件里读到的布尔常量默认值。 */
    private static boolean booleanField(Path file, String field) {
        Matcher m = Pattern.compile("private\\s+boolean\\s+" + Pattern.quote(field) + "\\s*=\\s*(true|false)")
                .matcher(stripComments(readText(file)));
        if (!m.find()) {
            throw new IllegalStateException(file + " 里找不到字段 " + field);
        }
        return Boolean.parseBoolean(m.group(1));
    }

    /** examples 的 application.yml 里 server.port。 */
    private static int examplesServerPort() {
        Matcher m = Pattern.compile("(?m)^server:[\\s\\S]*?^[ \\t]*port:[ \\t]*(\\d+)").matcher(readText(repoRoot().resolve(EXAMPLES_YML)));
        if (!m.find()) {
            throw new IllegalStateException(EXAMPLES_YML + " 里找不到 server.port");
        }
        return Integer.parseInt(m.group(1));
    }

    /** 编译插件的 source / target。 */
    private static int[] compilerSourceTarget() {
        Element build = child(rootProject(), "build");
        Element pm = child(build, "pluginManagement");
        for (Element plugins : children(pm, "plugins")) {
            for (Element p : children(plugins, "plugin")) {
                if ("maven-compiler-plugin".equals(text(child(p, "artifactId")))) {
                    Element cfg = child(p, "configuration");
                    return new int[] {
                            Integer.parseInt(text(child(cfg, "source"))),
                            Integer.parseInt(text(child(cfg, "target")))};
                }
            }
        }
        throw new IllegalStateException("根 pom 里找不到 maven-compiler-plugin 的 pluginManagement");
    }

    /** 全仓 .java 里命中某个正则的处数。 */
    private static int grepJavaCount(String regex) {
        int n = 0;
        for (Path p : allJavaFiles()) {
            Matcher m = Pattern.compile(regex).matcher(readText(p));
            while (m.find()) {
                n++;
            }
        }
        return n;
    }

    /** 全仓 pom（剥注释后）里命中某个正则的文件相对路径。 */
    private static List<String> pomFilesMentioning(String regex) {
        List<String> out = new ArrayList<String>();
        try (Stream<Path> s = Files.walk(repoRoot())) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                String path = p.toString();
                if (!"pom.xml".equals(p.getFileName().toString())) {
                    continue;
                }
                if (path.contains("/target/") || path.contains("/node_modules/")) {
                    continue;
                }
                if (Pattern.compile(regex).matcher(stripXmlComments(readText(p))).find()) {
                    out.add(repoRoot().relativize(p).toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 仓内部署资产（Dockerfile / compose / Makefile / k8s 清单）—— 排除 node_modules 与 target。 */
    private static List<String> deployAssets() {
        List<String> out = new ArrayList<String>();
        try (Stream<Path> s = Files.walk(repoRoot())) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                String path = p.toString();
                if (path.contains("/target/") || path.contains("/node_modules/") || path.contains("/.git/")) {
                    continue;
                }
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String n = p.getFileName().toString().toLowerCase();
                boolean asset = n.startsWith("dockerfile") || n.startsWith("docker-compose")
                        || n.startsWith("compose.") || n.startsWith("makefile")
                        || (n.endsWith(".yaml") || n.endsWith(".yml")) && readText(p).contains("apiVersion:");
                if (asset) {
                    out.add(repoRoot().relativize(p).toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 各测试类的 @Test 数（排除本守卫文件，与 README 给的口径一致）。 */
    private static Map<String, Integer> testCountsPerClass() {
        Map<String, Integer> out = new TreeMap<String, Integer>();
        for (String module : new String[] {"z-gw-core", "z-gw-spring-boot-starter"}) {
            Path dir = repoRoot().resolve(module).resolve("src/test/java");
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                    if (!p.getFileName().toString().endsWith(".java")
                            || GUARD_FILE.equals(p.getFileName().toString())) {
                        continue;
                    }
                    out.put(p.getFileName().toString().replace(".java", ""), countTestAnnotations(readText(p)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    /** 数独立成行的 @Test 注解（用拼接字面量，免得 README 贴的 rg 命令命中守卫自己）。 */
    private static int countTestAnnotations(String text) {
        String needle = "@" + "Test";
        int n = 0;
        for (String line : text.split("\n", -1)) {
            if (line.trim().equals(needle)) {
                n++;
            }
        }
        return n;
    }
    // ==================================================================
    // 事实对象（只装源码侧事实；README 侧的数值一律现解析）
    // ==================================================================

    private static final class Facts {
        String groupId;
        String artifactId;
        String version;
        String parentVersion;
        List<String> modules;
        Map<String, String> props;
        List<String[]> dm;
        int dmMicrometer;
        int dmProjectVersionPins;
        Set<String> dmProjectVersionNames;
        int[] sourceTarget;
        String surefireVersion;
        String flattenVersion;
        String centralPublishingVersion;
        Set<String> centralExcluded;
        List<String> subModules;
        int subModulesWithDeploySkip;
        int modulePomsDeclaringReactor;
        int pomsDeclaringWebflux;
        int apiClasses;
        int coreClasses;
        Set<String> predicateFactories;
        List<String> registeredFactories;
        Set<String> loadBalancers;
        Map<String, String> rateLimitAlgorithms;
        List<String> globalFilters;
        int adminControllers;
        Set<String> adminEndpoints;
        int serverPortDefault;
        int examplesWebPort;
        int readTimeoutSeconds;
        int trustedProxyHops;
        boolean corsEnabledDefault;
        int corsFieldCount;
        Set<String> healthPaths;
        int monoFluxHits;
        List<String> deployAssets;
        Map<String, Integer> testCountsPerClass;
    }

    private static Facts facts() {
        Path root = repoRoot();
        Element project = rootProject();
        Map<String, String> props = rootProperties();
        List<String[]> dm = rootManagedDeps();

        Facts f = new Facts();
        f.groupId = text(child(project, "groupId"));
        f.artifactId = text(child(project, "artifactId"));
        f.version = text(child(project, "version"));
        Element parent = child(project, "parent");
        f.parentVersion = text(child(parent, "version"));
        f.modules = pomModules();
        f.props = props;
        f.dm = dm;
        f.dmMicrometer = 0;
        f.dmProjectVersionPins = 0;
        f.dmProjectVersionNames = new TreeSet<String>();
        for (String[] d : dm) {
            if (d[0].contains("micro" + "meter")) {
                f.dmMicrometer++;
            }
            if (d[1].startsWith(ROOT_ARTIFACT_ID) && d[2].contains("project.version")) {
                f.dmProjectVersionPins++;
                f.dmProjectVersionNames.add(d[1]);
            }
        }
        f.sourceTarget = compilerSourceTarget();
        f.surefireVersion = managedPluginVersion("maven-surefire-plugin");
        f.flattenVersion = buildPluginVersion("flatten-maven-plugin");
        f.centralPublishingVersion = buildPluginVersion("central-publishing-maven-plugin");
        f.centralExcluded = centralExcludedArtifacts();

        f.subModules = subModuleNames();
        f.subModulesWithDeploySkip = 0;
        f.modulePomsDeclaringReactor = 0;
        f.pomsDeclaringWebflux = 0;
        for (String m : f.subModules) {
            String t = stripXmlComments(readText(root.resolve(m).resolve("pom.xml")));
            if (t.contains("maven.deploy.skip")) {
                f.subModulesWithDeploySkip++;
            }
            if (t.contains("reactor-netty") || t.contains("reactor-core")) {
                f.modulePomsDeclaringReactor++;
            }
            if (t.contains("web" + "flux")) {
                f.pomsDeclaringWebflux++;
            }
        }

        Path corePkg = root.resolve(CORE_MAIN).resolve(CORE_PKG);
        Path serverConfig = corePkg.resolve("config/ServerConfig.java");
        Path gatewayServer = corePkg.resolve("server/GatewayServer.java");
        Path gatewayHandler = corePkg.resolve("server/GatewayHandler.java");

        f.apiClasses = mainClassCountRecursive("z-gw-api", "com/zifang/z/gw/api");
        f.coreClasses = mainClassCountRecursive("z-gw-core", CORE_PKG);
        f.predicateFactories = classNamesWithoutSuffix(corePkg.resolve("predicate"), "PredicateFactory");
        f.registeredFactories = registeredFilterFactories();
        f.loadBalancers = classNamesWithoutSuffix(corePkg.resolve("lb"), "LoadBalancer");
        f.rateLimitAlgorithms = rateLimiterAlgorithms();
        f.globalFilters = globalFilterOrder();

        Path adminCtrl = root.resolve(ADMIN_MAIN).resolve("com/zifang/z/gw/admin/controller");
        f.adminControllers = javaFiles(adminCtrl).size();
        f.adminEndpoints = adminEndpoints();

        f.serverPortDefault = intField(serverConfig, "port");
        f.examplesWebPort = examplesServerPort();
        f.trustedProxyHops = intField(serverConfig, "trustedProxyHops");
        f.corsEnabledDefault = booleanField(serverConfig, "corsEnabled");

        Matcher rt = Pattern.compile("new\\s+ReadTimeoutHandler\\s*\\(\\s*(\\d+)\\s*,\\s*TimeUnit\\.SECONDS")
                .matcher(stripComments(readText(gatewayServer)));
        if (!rt.find()) {
            throw new IllegalStateException("GatewayServer 里找不到 ReadTimeoutHandler(..., SECONDS)");
        }
        f.readTimeoutSeconds = Integer.parseInt(rt.group(1));

        Set<String> healthPaths = new TreeSet<String>();
        String body = methodBody(stripComments(readText(gatewayHandler)), "isHealthPath(String path)");
        Matcher hp = Pattern.compile("\"(/[^\"]*)\"").matcher(body);
        while (hp.find()) {
            healthPaths.add(hp.group(1));
        }
        f.healthPaths = healthPaths;

        int corsFields = 0;
        String sc = stripComments(readText(serverConfig));
        Matcher cf = Pattern.compile("private\\s+\\w+\\s+cors[A-Za-z]*\\s*=").matcher(sc);
        while (cf.find()) {
            corsFields++;
        }
        f.corsFieldCount = corsFields;

        // 响应式类型名用拼接字面量：README 贴的「出现 0 次」复算命令不能把守卫自己数进去
        f.monoFluxHits = grepJavaCount("\\bMo" + "no\\b|\\bFl" + "ux\\b");
        f.deployAssets = deployAssets();
        f.testCountsPerClass = testCountsPerClass();
        return f;
    }

    // ==================================================================
    // 核对器：一次全报（收集齐所有不一致再抛，撞停会把尺子退化成「一次只说一句」）
    // ==================================================================

    private static final class Checker {
        final List<String> problems = new ArrayList<String>();

        void eq(String what, Object expected, Object actual) {
            if (!String.valueOf(expected).equals(String.valueOf(actual))) {
                problems.add(what + "：预期=" + expected + "，实算=" + actual);
            }
        }

        void eqSet(String what, Set<String> expected, Set<String> actual) {
            if (!new TreeSet<String>(expected).equals(new TreeSet<String>(actual))) {
                problems.add(what + "：预期=" + new TreeSet<String>(expected) + "，实算=" + new TreeSet<String>(actual));
            }
        }

        void eqList(String what, List<String> expected, List<String> actual) {
            if (!new ArrayList<String>(expected).equals(new ArrayList<String>(actual))) {
                problems.add(what + "：预期=" + expected + "，实算=" + actual);
            }
        }

        void failIfAny() {
            if (!problems.isEmpty()) {
                fail("README 与盘面不一致（" + problems.size() + " 条）：\n  - "
                        + String.join("\n  - ", problems));
            }
        }
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    @DisplayName("基本信息表：坐标 / 当前版本 / 父项目 / 默认端口 / reactor 模块名")
    void 基本信息表与根pom一致() {
        String readme = readme();
        Facts f = facts();
        Checker c = new Checker();
        String basic = section(readme, "基本信息");

        List<String> coord = rowCells(basic, "**Maven 坐标**");
        assertNotNull(coord, "[基本信息] 表格里没有「Maven 坐标」行");
        c.eq("[基本信息] 坐标 groupId", f.groupId, coord.get(1).contains(f.groupId) ? f.groupId : coord.get(1));
        c.eq("[基本信息] 坐标 artifactId", f.artifactId, coord.get(1).contains(":" + f.artifactId + ":") ? f.artifactId : coord.get(1));
        c.eq("[基本信息] 坐标版本", f.version, claim(coord.get(1), Pattern.compile(":" + Pattern.quote(f.artifactId) + ":([\\d.]+)"), "坐标版本"));
        assertTrue(coord.get(1).contains(":" + f.artifactId + ":" + f.version),
                "[基本信息] 坐标格没写出 " + f.groupId + ":" + f.artifactId + ":" + f.version + "：" + coord.get(1));

        List<String> ver = rowCells(basic, "**当前版本**");
        assertNotNull(ver, "[基本信息] 表格里没有「当前版本」行");
        assertTrue(ver.get(1).contains("`" + f.version + "`"),
                "[基本信息] 当前版本写 " + ver.get(1) + "，根 pom 是 " + f.version);

        List<String> par = rowCells(basic, "**父项目**");
        assertNotNull(par, "[基本信息] 表格里没有「父项目」行");
        assertTrue(par.get(1).contains("z-boot-parent:" + f.parentVersion),
                "[基本信息] 父项目写 " + par.get(1) + "，根 pom 是 z-boot-parent:" + f.parentVersion);

        List<String> port = rowCells(basic, "**默认端口**");
        assertNotNull(port, "[基本信息] 表格里没有「默认端口」行");
        assertTrue(port.get(1).contains("`" + f.serverPortDefault + "`"),
                "[基本信息] 默认端口没写网关端口 " + f.serverPortDefault + "：" + port.get(1));
        assertTrue(port.get(1).contains("`" + f.examplesWebPort + "`"),
                "[基本信息] 默认端口没写管理端口 " + f.examplesWebPort + "：" + port.get(1));

        List<String> reactor = rowCells(basic, "**reactor 模块**");
        assertNotNull(reactor, "[基本信息] 表格里没有「reactor 模块」行");
        int claimed = intClaim(reactor.get(1), Pattern.compile("(\\d+) 个"), "[基本信息] reactor 模块数");
        c.eq("[基本信息] reactor 模块数", f.modules.size(), claimed);
        for (String m : f.modules) {
            assertTrue(reactor.get(1).contains("`" + m + "`"),
                    "[基本信息] reactor 模块名单里没有 " + m + "：" + reactor.get(1));
        }
        c.failIfAny();
    }

    @Test
    @DisplayName("根 DM：${project.version} 自钉条数；子模块 POM 无 deploy.skip；预留槽位无人依赖")
    void 根依赖管理表与子模块一致() {
        String readme = readme();
        Facts f = facts();
        Checker c = new Checker();
        String struct = section(readme, "项目结构");

        // 「都在根 DM 里钉 ${project.version}」—— DM 条数与名单
        String dmLine = claim(struct, Pattern.compile("(\\d+) 个 Maven 子模块 POM"), "结构树后的子模块 POM 数");
        c.eq("[结构树] 子模块 POM 数", f.subModules.size(), Integer.parseInt(dmLine));
        c.eq("[根 DM] ${project.version} 自钉条数", f.modules.size() + 1, f.dmProjectVersionPins);
        for (String m : f.modules) {
            assertTrue(f.dmProjectVersionNames.contains(m),
                    "[根 DM] 根 DM 里没有 " + m + " 的 ${project.version} 自钉：" + f.dmProjectVersionNames);
        }
        c.eq("[结构树] 无 maven.deploy.skip 的子模块", 0, f.subModulesWithDeploySkip);
        c.eq("[如实边界] 声明 reactor-netty/reactor-core 的模块数", 0, f.modulePomsDeclaringReactor);
        c.eq("[如实边界] 声明 WebFlux 的 pom 数", 0, f.pomsDeclaringWebflux);
        c.eq("[技术栈] 根 DM 里的 micrometer 条数", 0, f.dmMicrometer);

        // central profile 的 excludeArtifacts：z-gw-admin 不在发布清单里
        assertTrue(f.centralExcluded.contains("z-gw-admin"),
                "[发布] central profile 的 excludeArtifacts 没排除 z-gw-admin：" + f.centralExcluded);
        assertTrue(struct.contains("excludeArtifacts") && struct.contains("z-gw-admin"),
                "[结构树] README 没说清 z-gw-admin 被 excludeArtifacts 排除：" + struct);
        c.failIfAny();
    }

    @Test
    @DisplayName("结构树：类数与测试类数逐个对得上")
    void 结构树规模与盘面一致() {
        String readme = readme();
        Facts f = facts();
        Checker c = new Checker();

        String apiLine = treeLine(readme, "z-gw-api");
        int apiClaim = intClaim(apiLine, Pattern.compile("（(\\d+) 个类）"), "[结构树] z-gw-api 类数");
        c.eq("[结构树] z-gw-api 类数", f.apiClasses, apiClaim);

        String coreLine = treeLine(readme, "z-gw-core");
        int coreClaim = intClaim(coreLine, Pattern.compile("（(\\d+) 个类"), "[结构树] z-gw-core 类数");
        c.eq("[结构树] z-gw-core 类数", f.coreClasses, coreClaim);
        int testClaim = intClaim(coreLine, Pattern.compile("(\\d+) 个测试类"), "[结构树] z-gw-core 测试类数");
        int realTestClasses = 0;
        Path coreTestDir = repoRoot().resolve(CORE_TEST);
        try (Stream<Path> walk = Files.walk(coreTestDir)) {
            for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                String n = p.getFileName().toString();
                if (n.endsWith(".java") && !GUARD_FILE.equals(n)) {
                    realTestClasses++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        c.eq("[结构树] z-gw-core 测试类数（口径排除本守卫文件）", realTestClasses, testClaim);

        // examples 被移出 <modules>
        assertTrue(!f.modules.contains("z-gw-examples"), "[基本信息] z-gw-examples 不该在 reactor <modules> 里");
        assertTrue(f.subModules.contains("z-gw-examples"), "[结构树] z-gw-examples 目录应带 pom.xml");
        c.failIfAny();
    }

    @Test
    @DisplayName("能力清单：谓词 / 负载均衡 / 限流算法 / 过滤器工厂 / 全局过滤器顺序")
    void 能力清单与盘面一致() {
        String readme = readme();
        Facts f = facts();
        Checker c = new Checker();
        String cap = section(readme, "能力清单（逐条对应代码）");

        // 谓词：内置 6 个
        assertTrue(cap.contains("内置 " + f.predicateFactories.size() + " 个"),
                "[能力清单] 谓词数量与盘面不符（盘面 " + f.predicateFactories.size() + " 个）");
        // 负载均衡：逐个点名（README 的排列顺序是逻辑分组序，不当主张比）
        for (String lb : f.loadBalancers) {
            assertTrue(cap.contains("`" + lb + "`"),
                    "[能力清单] 负载均衡 " + lb + " 没写进 README：" + f.loadBalancers);
        }
        int lbClaim = intClaim(treeLine(readme, "lb"), Pattern.compile("(\\d+) 种负载均衡"), "[结构树] 负载均衡种数");
        c.eq("[结构树] 负载均衡种数", f.loadBalancers.size(), lbClaim);
        // 限流算法：三种
        for (Map.Entry<String, String> e : f.rateLimitAlgorithms.entrySet()) {
            assertTrue(cap.contains(e.getValue()),
                    "[能力清单] 限流算法 " + e.getValue() + " 没写进 README");
        }
        // 过滤器工厂：8 个（README 说「8 个工厂」）
        int factoryClaim = intClaim(treeLine(readme, "filter"), Pattern.compile("、(\\d+) 个工厂、"), "[结构树] 过滤器工厂数");
        c.eq("[能力清单] 过滤器工厂数", f.registeredFactories.size(), factoryClaim);
        // 全局过滤器顺序：README 括号里列的顺序必须与源码逐个一致
        String order = claim(cap, Pattern.compile("顺序 ([^）；]+)"), "[能力清单] 全局过滤器顺序");
        List<String> readmeOrder = new ArrayList<String>();
        for (String s : order.split("→")) {
            readmeOrder.add(s.trim());
        }
        if (readmeOrder.size() != f.globalFilters.size()) {
            c.eqList("[能力清单] 全局过滤器顺序", f.globalFilters, readmeOrder);
        } else {
            for (int i = 0; i < readmeOrder.size(); i++) {
                String src = f.globalFilters.get(i);
                String rdm = readmeOrder.get(i);
                if (!src.startsWith(rdm) && !rdm.startsWith(src)) {
                    c.eq("[能力清单] 全局过滤器顺序第 " + (i + 1) + " 位", src, rdm);
                }
            }
        }
        c.failIfAny();
    }

    @Test
    @DisplayName("admin：Controller 数与管理端点集合")
    void 管理端点与README一致() {
        String readme = readme();
        Facts f = facts();
        Checker c = new Checker();
        String cap = section(readme, "能力清单（逐条对应代码）");

        String ctrlCn = claim(cap, Pattern.compile("z-gw-admin` (\\S+?)个 Controller"), "[能力清单] admin Controller 数");
        c.eq("[能力清单] admin Controller 数", f.adminControllers, chineseNum(ctrlCn));

        // API 一览表：路径行里的方法+路径，必须都在盘面端点集合里
        String api = section(readme, "API 一览");
        int covered = 0;
        for (String line : api.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("| `")) {
                continue;
            }
            String[] cells = splitRow(t);
            if (cells.length < 2) {
                continue;
            }
            Matcher pm2 = Pattern.compile("/gw/admin[A-Za-z0-9/{}._-]*").matcher(cells[0]);
            while (pm2.find()) {
                String path = pm2.group();
                boolean any = false;
                for (String ep : f.adminEndpoints) {
                    if (ep.endsWith(" " + path)) {
                        any = true;
                        break;
                    }
                }
                assertTrue(any, "[API 一览] README 列了 " + path + "，盘面没有这个端点：" + f.adminEndpoints);
                covered++;
            }
        }
        assertTrue(covered >= 9, "[API 一览] 只核到 " + covered + " 条路径，表可能没解析对");
        c.failIfAny();
    }

    @Test
    @DisplayName("运行时默认值：健康路径 / CORS 字段数与默认值 / 读超时 / 可信代理跳数")
    void 运行时默认值与README一致() {
        String readme = readme();
        Facts f = facts();
        Checker c = new Checker();
        String cap = section(readme, "能力清单（逐条对应代码）");
        String limit = section(readme, "已知限制与行为变更");

        // 健康路径三个
        for (String p : f.healthPaths) {
            assertTrue(cap.contains("`" + p + "`"),
                    "[能力清单] 健康路径 " + p + " 没写进 README：" + f.healthPaths);
        }
        // 读超时 60 秒
        assertTrue(limit.contains("固定 " + f.readTimeoutSeconds + " 秒"),
                "[已知限制] 读超时秒数与盘面不符（盘面 " + f.readTimeoutSeconds + " 秒）");
        // 可信代理跳数默认 0
        assertTrue(cap.contains("**默认 " + f.trustedProxyHops + " = 不采信任何请求头**"),
                "[能力清单] trustedProxyHops 默认值与盘面不符：" + f.trustedProxyHops);
        // cors-* 字段数
        String corsCn = claim(cap, Pattern.compile("`zgw\\.server\\.cors-\\*` (\\S+?)个配置项"), "[如实边界] cors 字段数");
        c.eq("[如实边界] cors-* 字段数", f.corsFieldCount, chineseNum(corsCn));
        // CORS 默认关闭
        assertTrue(cap.contains("**默认关闭**"), "[能力清单] 没写 CORS 默认关闭");
        assertEquals(false, f.corsEnabledDefault, "[运行时] corsEnabled 默认值不是 false");
        c.failIfAny();
    }

    @Test
    @DisplayName("如实边界：响应式类型 0 命中、无部署资产、JWT / WebSocket / 发现未实现")
    void 如实边界成立() {
        String readme = readme();
        Facts f = facts();
        Checker c = new Checker();
        String edge = section(readme, "如实边界（原 README 宣传过、代码里实际没有的东西）");

        c.eq("[如实边界] 全仓响应式类型命中数", 0, f.monoFluxHits);
        assertTrue(edge.contains("出现 0 次"), "[如实边界] 没写响应式类型 0 命中");
        c.eq("[如实边界] 仓内部署资产数", 0, f.deployAssets.size());
        assertTrue(edge.contains("本仓没有 Dockerfile"), "[如实边界] 没写无部署资产");

        // WebSocket 开关：字段与 getter 都在，但 getter 全仓无人调用
        int wsGetter = 0;
        for (Path p : allJavaFiles()) {
            String path = p.toString();
            if (path.endsWith("config/ServerConfig.java")) {
                continue;
            }
            if (Pattern.compile("\\b(is|get)Web" + "socketEnabled\\s*\\(")
                    .matcher(stripComments(readText(p))).find()) {
                wsGetter++;
            }
        }
        c.eq("[如实边界] WebSocket 开关 getter 被调用的处数", 0, wsGetter);

        // 属性类真名是 GatewayProperties；仓里没有另一个拼法的类
        assertTrue(Files.isRegularFile(repoRoot().resolve(CORE_MAIN).resolve(CORE_PKG).resolve("config/GatewayProperties.java")),
                "[如实边界] 找不到 GatewayProperties.java");
        Path bogus = repoRoot().resolve(CORE_MAIN).resolve(CORE_PKG).resolve("config/Z" + "GatewayProperties.java");
        assertFalse(Files.exists(bogus), "[如实边界] 仓里居然有个 ZGatewayProperties.java");
        assertTrue(edge.contains("`GatewayProperties`"),
                "[如实边界] README 没写属性类真名 GatewayProperties：" + edge);
        assertTrue(edge.contains("`Z" + "GatewayProperties`"),
                "[如实边界] README 应点明仓里没有 ZGatewayProperties 这个类：" + edge);

        // 注册中心那类配置键在属性类里不存在
        int discoveryKey = grepJavaCount("zgw\\.dis" + "covery");
        c.eq("[如实边界] 注册中心配置键命中数", 0, discoveryKey);

        // 出站无 TLS：出站客户端的 pipeline 里没有 TLS handler（剥注释后判，注释里的说明不算）
        Path outClient = repoRoot().resolve(CORE_MAIN).resolve(CORE_PKG).resolve("http/BackendHttpClient.java");
        int ssl = 0;
        Matcher sslm = Pattern.compile("\\bSsl" + "Handler\\b").matcher(stripComments(readText(outClient)));
        while (sslm.find()) {
            ssl++;
        }
        c.eq("[已知限制] 出站客户端里的 TLS handler 数", 0, ssl);
        c.failIfAny();
    }

    @Test
    @DisplayName("技术栈：插件版本、预留槽位版本、编译级别")
    void 技术栈版本与盘面一致() {
        String readme = readme();
        Facts f = facts();
        Checker c = new Checker();
        String tech = section(readme, "技术栈（pom 实测）");

        assertTrue(tech.contains("flatten-maven-plugin " + f.flattenVersion),
                "[技术栈] flatten 版本与根 pom 不符：" + f.flattenVersion);
        assertTrue(tech.contains("central-publishing-maven-plugin " + f.centralPublishingVersion),
                "[技术栈] central-publishing 版本与根 pom 不符：" + f.centralPublishingVersion);
        assertTrue(tech.contains("surefire " + f.surefireVersion),
                "[技术栈] surefire 版本与根 pom 不符：" + f.surefireVersion);
        // 编译级别 8
        assertTrue(tech.contains("compiler source/target " + f.sourceTarget[0]),
                "[技术栈] 编译级别与根 pom 不符：" + f.sourceTarget[0]);
        // 预留槽位版本（根 DM 里的属性）
        for (String key : new String[] {"reactor-netty.version", "projectreactor.version", "slf4j.version"}) {
            assertTrue(f.props.containsKey(key), "[技术栈] 根 pom 没有属性 " + key);
            assertTrue(tech.contains(f.props.get(key)),
                    "[技术栈] README 没写 " + key + " = " + f.props.get(key));
        }
        // Micrometer：根 DM 不压版本，README 必须写当前生效的 1.9.17
        List<String> micro = rowCells(tech, "指标");
        assertNotNull(micro, "[技术栈] 技术栈表里没有「指标」行");
        assertTrue(micro.get(1).contains("1.9.17"),
                "[技术栈] Micrometer 版本没写当前生效的 1.9.17：" + micro.get(1));
        assertFalse(micro.get(1).contains("1.12.13"),
                "[技术栈] Micrometer 还写着已收回的 1.12.13：" + micro.get(1));
        assertFalse(micro.get(1).contains("根 DM 压过"),
                "[技术栈] Micrometer 行还声称根 DM 压过地板（根 DM 里没有 micrometer）：" + micro.get(1));

        // 根 DM 里的 reactor 预留槽位确实只是槽位
        assertEquals(0, f.modulePomsDeclaringReactor, "[技术栈] 有模块真的依赖了 reactor");
        c.failIfAny();
    }

    @Test
    @DisplayName("测试段：逐行 @Test 数与总数")
    void 测试表与盘面一致() {
        String readme = readme();
        Facts f = facts();
        Checker c = new Checker();
        String sec = section(readme, "测试");

        int rows = 0;
        int total = 0;
        for (String line : sec.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("| `")) {
                continue;
            }
            String[] cells = splitRow(t);
            if (cells.length < 2) {
                continue;
            }
            String cls = cells[0].replace("`", "").trim();
            Integer n = f.testCountsPerClass.get(cls);
            assertNotNull(n, "[测试] 表里列了 " + cls + "，盘面没有这个测试类：" + f.testCountsPerClass.keySet());
            int claimed = Integer.parseInt(cells[1].trim());
            c.eq("[测试] " + cls + " 的 @Test 数", n, claimed);
            total += claimed;
            rows++;
        }
        assertTrue(rows >= 20, "[测试] 只核到 " + rows + " 行，表可能没解析对");
        int totalClaim = intClaim(sec, Pattern.compile("(\\d+) 个 `@Test`"), "[测试] @Test 总数");
        c.eq("[测试] @Test 总数", total, totalClaim);
        c.failIfAny();
    }

    @Test
    @DisplayName("不许冤枉健康页：真实 README 不得被判红")
    void 不许冤枉健康页() {
        // 真实 README 逐条过一遍核对器：任何一条不符都必须在这里报出来，
        // 不能等到「恰好改了某个数」时才暴露
        String readme = readme();
        Facts f = facts();
        Checker c = new Checker();
        checkBasicInfo(readme, f, c);
        c.failIfAny();
    }

    @Test
    @DisplayName("尺子的牙口：把 README 的坐标改错，判定必须判红")
    void 尺子的牙口() {
        String readme = readme();
        Facts f = facts();
        String mutated = readme.replace(f.groupId + ":" + f.artifactId + ":" + f.version,
                f.groupId + ":" + f.artifactId + ":9.9.9");
        assertNotEquals(readme, mutated, "变异没改到点上（坐标串没找到）");

        Checker c = new Checker();
        checkBasicInfo(mutated, f, c);
        assertFalse(c.problems.isEmpty(), "尺子没咬住：把坐标版本改成 9.9.9 竟然全绿");
        assertTrue(c.problems.get(0).contains("9.9.9"),
                "尺子咬住的不是被篡改的那条主张：" + c.problems.get(0));
    }

    /** 基本信息的核对逻辑（真实 README 与合成 README 共用，保证牙口测的是同一条路径）。 */
    private static void checkBasicInfo(String readme, Facts f, Checker c) {
        String basic = section(readme, "基本信息");
        List<String> coord = rowCells(basic, "**Maven 坐标**");
        if (coord == null) {
            c.eq("[基本信息] 坐标行存在", true, false);
            return;
        }
        String expected = f.groupId + ":" + f.artifactId + ":" + f.version;
        c.eq("[基本信息] 坐标", expected, coord.get(1).contains(expected) ? expected : coord.get(1));
        List<String> ver = rowCells(basic, "**当前版本**");
        if (ver == null) {
            c.eq("[基本信息] 当前版本行存在", true, false);
            return;
        }
        c.eq("[基本信息] 当前版本", f.version, ver.get(1).contains("`" + f.version + "`") ? f.version : ver.get(1));
    }
}
