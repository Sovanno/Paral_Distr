import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) throws IOException {

        Map<String, Set<String>> classes = new HashMap<>();

        Files.walk(Paths.get("Test")).filter(p -> p.toString().endsWith(".java"))
                .forEach(path -> {

                    String code = null;
                    try {
                        code = Files.readString(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    code = code.replaceAll("(?s)/\\*.*?\\*/", "");
                    code = code.replaceAll("(?m)//.*?$", "");

                    Pattern p = Pattern.compile(
                            "\\b(class|interface)\\b\\s+(\\w+)" +
                            "(?:\\b\\s+extends\\s+([\\w\\.]+))?" +
                            "(?:\\b\\s+implements\\s+([\\w\\.,\\s]+))?");

                    Matcher m = p.matcher(code);

                    while (m.find()){
                        String className = m.group(2);
                        String extendsPart = m.group(3);
                        String implementsPart = m.group(4);

                        if (extendsPart != null) {
                            Set<String> set = classes.getOrDefault(extendsPart, new HashSet<>());
                            set.add(className);
                            classes.put(extendsPart, set);
                        }

                        if (implementsPart != null) {
                            Set<String> set = classes.getOrDefault(implementsPart, new TreeSet<>());
                            set.add(className);
                            classes.put(implementsPart, set);
                        }
                    }
                });

         classes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println(e.getKey() + " -> " + e.getValue()));
    }

}
