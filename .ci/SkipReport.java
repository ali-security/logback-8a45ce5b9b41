// Prints a per-test skip report and pass/fail totals from surefire XML reports.
// Added by the sealed build (not upstream): the surefire console output only gives
// skip counts, so this names every skipped test with the reason found in its source.
// Usage: java .ci/SkipReport.java <module-dir> [<module-dir> ...]
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class SkipReport {

    public static void main(String[] args) throws Exception {
        long tTests = 0, tFail = 0, tErr = 0, tSkip = 0;
        List<String> moduleLines = new ArrayList<>();
        List<String> skipLines = new ArrayList<>();
        for (String module : args) {
            File dir = new File(module, "target/surefire-reports");
            File[] files = dir.listFiles((d, n) -> n.startsWith("TEST-") && n.endsWith(".xml"));
            long tests = 0, fail = 0, err = 0, skip = 0;
            if (files != null) {
                for (File f : files) {
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    Document doc = dbf.newDocumentBuilder().parse(f);
                    NodeList cases = doc.getElementsByTagName("testcase");
                    for (int i = 0; i < cases.getLength(); i++) {
                        Element tc = (Element) cases.item(i);
                        tests++;
                        if (tc.getElementsByTagName("failure").getLength() > 0) {
                            fail++;
                        } else if (tc.getElementsByTagName("error").getLength() > 0) {
                            err++;
                        } else if (tc.getElementsByTagName("skipped").getLength() > 0) {
                            skip++;
                            Element sk = (Element) tc.getElementsByTagName("skipped").item(0);
                            String cls = tc.getAttribute("classname");
                            String name = tc.getAttribute("name").replaceAll("\\(.*$", "");
                            String reason = sk.getAttribute("message");
                            if (reason == null || reason.isEmpty() || reason.endsWith("is @Disabled")) {
                                reason = reasonFromSource(module, cls, name);
                            }
                            skipLines.add("SKIPPED " + module + " " + cls + "#" + name + ": " + reason);
                        }
                    }
                }
            }
            long passed = tests - fail - err - skip;
            moduleLines.add("MODULE " + module + ": tests=" + tests + " passed=" + passed + " failures=" + fail
                    + " errors=" + err + " skipped=" + skip);
            tTests += tests;
            tFail += fail;
            tErr += err;
            tSkip += skip;
        }
        System.out.println("===== SKIPPED TESTS (upstream-disabled / assumption) =====");
        skipLines.forEach(System.out::println);
        System.out.println("===== TEST RESULTS PER MODULE =====");
        moduleLines.forEach(System.out::println);
        System.out.println("TOTAL: tests=" + tTests + " passed=" + (tTests - tFail - tErr - tSkip) + " failures=" + tFail
                + " errors=" + tErr + " skipped=" + tSkip);
    }

    static String reasonFromSource(String module, String cls, String method) {
        String outer = cls.replaceAll("\\$.*$", "");
        File src = new File(module, "src/test/java/" + outer.replace('.', '/') + ".java");
        if (!src.isFile()) {
            return "skipped by JUnit (no message; source not found)";
        }
        try {
            List<String> lines = Files.readAllLines(src.toPath(), StandardCharsets.UTF_8);
            Pattern m = Pattern.compile("\\bvoid\\s+" + Pattern.quote(method) + "\\s*\\(");
            for (int i = 0; i < lines.size(); i++) {
                Matcher mm = m.matcher(lines.get(i));
                if (!mm.find()) {
                    continue;
                }
                StringBuilder comment = new StringBuilder();
                for (int j = i - 1; j >= Math.max(0, i - 25); j--) {
                    String l = lines.get(j).trim();
                    if (l.startsWith("@Disabled") || l.startsWith("@Ignore")) {
                        String r = "upstream " + l + " (line " + (j + 1) + ")";
                        if (comment.length() > 0 && !l.contains("//") && !l.contains("(")) {
                            String c = comment.toString().trim();
                            r += "; source comment: " + (c.length() > 200 ? c.substring(0, 200) + "..." : c);
                        }
                        return r;
                    }
                    boolean isComment = l.startsWith("//") || l.startsWith("*") || l.startsWith("/*");
                    if (isComment) {
                        comment.insert(0, l.replaceAll("^[/*]+\\s*", "") + " ");
                    } else if (l.endsWith("}") || l.endsWith(";")) {
                        break;
                    }
                }
                for (int j = i + 1; j < Math.min(lines.size(), i + 20); j++) {
                    String l = lines.get(j).trim();
                    if (l.contains("assume")) {
                        return "JUnit assumption not met: " + l + " (line " + (j + 1) + ")";
                    }
                }
                break;
            }
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (l.startsWith("@Disabled") || l.startsWith("@Ignore")) {
                    for (int j = i + 1; j < Math.min(lines.size(), i + 4); j++) {
                        if (lines.get(j).contains("class ")) {
                            return "upstream class-level " + l + " (line " + (i + 1) + ")";
                        }
                    }
                }
            }
        } catch (Exception e) {
            return "skipped by JUnit (no message)";
        }
        return "skipped by JUnit (no message in report or source)";
    }
}
